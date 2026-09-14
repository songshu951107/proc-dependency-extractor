package com.hb.extractor.parser;

import com.hb.extractor.service.TableColumnMetaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DDL 解析器（字段列表来源）
 *
 * 解析顺序：
 *   1. 数据库 table_column_meta（推荐，由 columns0818.xlsx 导入得到完整字段元数据）
 *   2. 回退到本地 _ddl.TXT 文件（GBK，仅字段名，向后兼容）
 *
 * 调用方（如 Neo4jSyncServiceImpl）使用方式不变，仍调用 parse(tableShort)。
 */
@Slf4j
@Component
public class DdlParser {

    @Autowired
    private TableColumnMetaService tableColumnMetaService;

    @Value("${extractor.ddl.dir:d:/HB/work_zuoye/jiaoben}")
    private String ddlDir;

    /** 旧的 _ddl.TXT 文件为 GBK 编码（COMMENT 含中文） */
    private static final Charset DDL_CHARSET = Charset.forName("GBK");

    /** 匹配 DDL 行中首个双引号包裹的字段名，跳过 CREATE TABLE 行 */
    private static final Pattern FIELD_PATTERN = Pattern.compile("^\\s*\"(\\w+)\"");

    /**
     * 按目标表短名查询字段列表。
     * 优先走数据库，DB 没有则回退到本地 _ddl.TXT 文件。
     *
     * @param tableShort 目标表短名（如 C_AGT_DEPOSIT_ACCT_INF）
     * @return 字段名列表（小写，DDL 顺序）；都没有返回 null
     */
    public List<String> parse(String tableShort) {
        if (tableShort == null || tableShort.isEmpty()) {
            return null;
        }
        // 1. 优先走数据库
        try {
            List<String> dbFields = tableColumnMetaService.getFieldNames(tableShort);
            if (dbFields != null && !dbFields.isEmpty()) {
                return dbFields;
            }
        } catch (Exception e) {
            // DB 查询失败不影响主流程，降级到文件
            log.warn("从数据库读取字段元数据失败 table={}, 降级到文件: {}", tableShort, e.getMessage());
        }
        // 2. 回退到本地文件
        return parseFromFile(tableShort);
    }

    /**
     * 从本地 _ddl.TXT 文件解析字段列表（向后兼容）
     */
    private List<String> parseFromFile(String tableShort) {
        String fileName = tableShort.toLowerCase() + "_ddl.TXT";
        Path ddlPath = Paths.get(ddlDir, fileName);
        if (!Files.exists(ddlPath)) {
            log.debug("DDL 文件不存在: {}", ddlPath);
            return null;
        }
        try {
            String content = Files.readString(ddlPath, DDL_CHARSET);
            return parseInline(content);
        } catch (IOException e) {
            log.warn("读取 DDL 文件失败: {}, {}", ddlPath, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 DDL 文本内容，提取字段列表
     *
     * @param ddlContent DDL 原文
     * @return 字段名列表（按 DDL 顺序）
     */
    public List<String> parseInline(String ddlContent) {
        List<String> fields = new ArrayList<>();
        if (ddlContent == null || ddlContent.isEmpty()) {
            return fields;
        }
        String[] lines = ddlContent.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            // 跳过 CREATE TABLE 行、括号行、约束行
            if (trimmed.isEmpty() || trimmed.toUpperCase().startsWith("CREATE TABLE")
                    || trimmed.equals("(") || trimmed.equals(")")
                    || trimmed.startsWith(");") || trimmed.startsWith("--")
                    || trimmed.toUpperCase().startsWith("PRIMARY KEY")
                    || trimmed.toUpperCase().startsWith("CONSTRAINT")
                    || trimmed.toUpperCase().startsWith("KEY")
                    || trimmed.toUpperCase().startsWith("UNIQUE")
                    || trimmed.toUpperCase().startsWith("INDEX")) {
                continue;
            }
            Matcher m = FIELD_PATTERN.matcher(line);
            if (m.find()) {
                fields.add(m.group(1).toLowerCase());
            }
        }
        return fields;
    }
}
