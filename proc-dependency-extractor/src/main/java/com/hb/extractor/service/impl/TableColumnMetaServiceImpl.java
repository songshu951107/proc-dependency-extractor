package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.entity.TableColumnMeta;
import com.hb.extractor.entity.TableDdlInfo;
import com.hb.extractor.mapper.TableColumnMetaMapper;
import com.hb.extractor.mapper.TableDdlInfoMapper;
import com.hb.extractor.parser.TableColumnExcelParser;
import com.hb.extractor.service.TableColumnMetaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class TableColumnMetaServiceImpl implements TableColumnMetaService {

    @Autowired
    private TableColumnExcelParser excelParser;

    @Autowired
    private TableDdlInfoMapper ddlInfoMapper;

    @Autowired
    private TableColumnMetaMapper columnMetaMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportResult importFromExcel(String xlsxPath) {
        long start = System.currentTimeMillis();
        ImportResult result = new ImportResult();
        result.sourceFile = xlsxPath;
        result.failures = new ArrayList<>();

        Path path = Paths.get(xlsxPath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("xlsx 文件不存在: " + xlsxPath);
        }

        // 清空旧数据（幂等）
        log.info("清空旧表元数据...");
        columnMetaMapper.delete(new LambdaQueryWrapper<>());
        ddlInfoMapper.delete(new LambdaQueryWrapper<>());

        Map<String, List<TableColumnMeta>> grouped;
        try {
            grouped = excelParser.parse(path);
        } catch (Exception e) {
            throw new RuntimeException("解析 xlsx 失败: " + e.getMessage(), e);
        }

        LocalDateTime now = LocalDateTime.now();
        int totalColumns = 0;
        for (Map.Entry<String, List<TableColumnMeta>> entry : grouped.entrySet()) {
            List<TableColumnMeta> cols = entry.getValue();
            if (cols.isEmpty()) continue;
            TableColumnMeta first = cols.get(0);

            try {
                TableDdlInfo ddl = new TableDdlInfo();
                ddl.setSchemaName(first.getSchemaName());
                ddl.setTableName(first.getTableName());
                ddl.setFullTableName(first.getFullTableName());
                ddl.setColumnCount(cols.size());
                ddl.setFieldsJson(toFieldsJson(cols));
                ddl.setDdlText(buildCreateTableDdl(first.getFullTableName(), cols));
                ddl.setSourceFile(xlsxPath);
                ddl.setParseTime(now);
                ddlInfoMapper.insert(ddl);

                for (TableColumnMeta c : cols) {
                    c.setDdlInfoId(ddl.getId());
                    columnMetaMapper.insert(c);
                }
                totalColumns += cols.size();
                result.tableCount++;
            } catch (Exception e) {
                log.error("入库失败 table={}", first.getFullTableName(), e);
                result.failures.add(first.getFullTableName() + ": " + e.getMessage());
            }
        }

        result.columnCount = totalColumns;
        result.elapsedMs = System.currentTimeMillis() - start;
        log.info("导入完成: {} 张表, {} 字段, 耗时 {} ms", result.tableCount, result.columnCount, result.elapsedMs);
        return result;
    }

    @Override
    public List<String> getFieldNames(String tableShort) {
        if (tableShort == null || tableShort.isEmpty()) return new ArrayList<>();
        return columnMetaMapper.selectFieldNamesByTable(tableShort.toLowerCase());
    }

    @Override
    public List<TableColumnMeta> getColumns(String tableShort) {
        if (tableShort == null || tableShort.isEmpty()) return new ArrayList<>();
        return columnMetaMapper.selectByTable(tableShort.toLowerCase());
    }

    @Override
    public String getDdlText(String tableShort) {
        if (tableShort == null || tableShort.isEmpty()) return null;
        TableDdlInfo ddl = ddlInfoMapper.selectOne(
                new LambdaQueryWrapper<TableDdlInfo>()
                        .eq(TableDdlInfo::getTableName, tableShort.toLowerCase())
                        .last("LIMIT 1"));
        return ddl == null ? null : ddl.getDdlText();
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> s = new HashMap<>();
        Long tableCount = ddlInfoMapper.selectCount(null);
        Long columnCount = columnMetaMapper.selectCount(null);
        s.put("tableCount", tableCount);
        s.put("columnCount", columnCount);
        return s;
    }

    /** 字段名 JSON 数组 */
    private String toFieldsJson(List<TableColumnMeta> cols) {
        List<String> names = new ArrayList<>(cols.size());
        for (TableColumnMeta c : cols) {
            names.add(c.getColumnName());
        }
        try {
            return objectMapper.writeValueAsString(names);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /**
     * 根据字段元数据生成 CREATE TABLE 语句。
     * 形如：
     *   CREATE TABLE `schema`.`table` (
     *     `col1` varchar(64) NOT NULL COMMENT 'xxx',
     *     `col2` bigint(20) DEFAULT NULL,
     *     PRIMARY KEY (`col1`)
     *   ) COMMENT='表注释';
     */
    private String buildCreateTableDdl(String fullTableName, List<TableColumnMeta> cols) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE `").append(fullTableName).append("` (\n");
        List<String> primaryKeys = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            TableColumnMeta c = cols.get(i);
            sb.append("  `").append(c.getColumnName()).append("` ");
            sb.append(c.getColumnType() != null ? c.getColumnType() : (c.getDataType() != null ? c.getDataType() : "varchar(255)"));
            // 处理 NULL/NOT NULL + DEFAULT，避免重复 DEFAULT
            String dv = c.getColumnDefault();
            boolean hasDefault = dv != null && !dv.isEmpty();
            if ("NO".equalsIgnoreCase(c.getIsNullable())) {
                sb.append(" NOT NULL");
                if (hasDefault) {
                    sb.append(" DEFAULT ").append(formatDefault(dv));
                }
            } else {
                // 可空：有默认值写 DEFAULT xxx，否则写 DEFAULT NULL
                if (hasDefault) {
                    sb.append(" DEFAULT ").append(formatDefault(dv));
                } else {
                    sb.append(" DEFAULT NULL");
                }
            }
            if (c.getExtra() != null && !c.getExtra().isEmpty()) {
                sb.append(" ").append(c.getExtra());
            }
            if (c.getColumnComment() != null && !c.getColumnComment().isEmpty()) {
                sb.append(" COMMENT '").append(c.getColumnComment().replace("'", "''")).append("'");
            }
            if (i < cols.size() - 1) sb.append(",");
            sb.append("\n");
            if ("PRI".equalsIgnoreCase(c.getColumnKey())) {
                primaryKeys.add(c.getColumnName());
            }
        }
        if (!primaryKeys.isEmpty()) {
            sb.append("  ,PRIMARY KEY (`")
              .append(String.join("`,`", primaryKeys))
              .append("`)\n");
        }
        sb.append(");");
        return sb.toString();
    }

    /** 格式化 DEFAULT 值：数字/函数直接写，其他加单引号 */
    private String formatDefault(String dv) {
        if (dv == null) return "NULL";
        String t = dv.trim();
        // 函数或特殊值（CURRENT_TIMESTAMP 等）不加引号
        if (t.equalsIgnoreCase("CURRENT_TIMESTAMP")
                || t.equalsIgnoreCase("NULL")
                || t.equalsIgnoreCase("CURRENT_DATE")) {
            return t;
        }
        // 数字直接写
        if (t.matches("-?\\d+(\\.\\d+)?")) {
            return t;
        }
        return "'" + t.replace("'", "''") + "'";
    }
}
