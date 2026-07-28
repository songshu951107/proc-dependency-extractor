package com.hb.extractor.parser;

import lombok.extern.slf4j.Slf4j;
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
 * DDL 文件解析器
 *
 * 从 DDL txt 文件中提取字段列表。
 * DDL 文件查找规则：目标表短名小写 + "_ddl.TXT"
 * 正则提取每行首个 "field_name" 引号内容。
 */
@Slf4j
@Component
public class DdlParser {

    @Value("${extractor.ddl.dir:d:/HB/work_zuoye/jiaoben}")
    private String ddlDir;

    /** DDL 文件实际为 GBK 编码（COMMENT 含中文），UTF-8 解码会抛 Input length = 1 */
    private static final Charset DDL_CHARSET = Charset.forName("GBK");

    /** 匹配 DDL 行中首个双引号包裹的字段名，跳过 CREATE TABLE 行 */
    private static final Pattern FIELD_PATTERN = Pattern.compile("^\\s*\"(\\w+)\"");

    /**
     * 按目标表短名查找 DDL 文件并解析字段列表
     *
     * @param tableShort 目标表短名（如 C_AGT_DEPOSIT_ACCT_INF）
     * @return 字段名列表（DDL 顺序），文件不存在返回 null
     */
    public List<String> parse(String tableShort) {
        if (tableShort == null || tableShort.isEmpty()) {
            return null;
        }
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
