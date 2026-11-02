package com.hb.extractor.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.util.IOUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 表结构 Excel → 合成 DDL 转换器（映射配置批量导入用）。
 *
 * 弹性表头识别：在前 10 行内找同时含「表名类」与「列名类」的表头行——
 *   表名类：TABLE_NAME / 表名
 *   列名类：COLUMN_NAME / 列名 / 字段名 / 字段名称 / 字段
 * 同一表内按行序即列序。输出合成 DDL（列类型统一 VARCHAR(1)，仅列名参与推导）。
 */
public final class TableStructureExcelParser {

    private static final Pattern TABLE_HDR = Pattern.compile("(?i)table_name|^表名$");
    private static final Pattern COLUMN_HDR = Pattern.compile("(?i)column_name|列名|字段名|字段名称|^字段$");

    private TableStructureExcelParser() { }

    public static String toDdl(InputStream in) {
        IOUtils.setByteArrayMaxOverride(256 * 1024 * 1024);
        Map<String, List<String>> tables = new LinkedHashMap<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                parseSheet(wb.getSheetAt(s), tables);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Excel 解析失败: " + e.getMessage(), e);
        }
        if (tables.isEmpty()) {
            throw new IllegalArgumentException(
                    "Excel 中未识别出表结构：需含「表名/TABLE_NAME」与「列名/COLUMN_NAME/字段名」表头行");
        }
        StringBuilder ddl = new StringBuilder();
        for (Map.Entry<String, List<String>> e : tables.entrySet()) {
            ddl.append("CREATE TABLE ").append(e.getKey()).append(" (\n");
            for (int i = 0; i < e.getValue().size(); i++) {
                ddl.append(i > 0 ? ",\n" : "").append(e.getValue().get(i)).append(" VARCHAR(1)");
            }
            ddl.append("\n);\n");
        }
        return ddl.toString();
    }

    private static void parseSheet(Sheet sheet, Map<String, List<String>> tables) {
        int lastRow = sheet.getLastRowNum();
        // 定位表头行
        int headerRow = -1, tableIdx = -1, colIdx = -1;
        for (int r = 0; r <= Math.min(10, lastRow); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int t = -1, c = -1;
            for (int i = 0; i < row.getLastCellNum(); i++) {
                String v = cellString(row, i);
                if (v == null) continue;
                String trimmed = v.trim();
                if (t < 0 && TABLE_HDR.matcher(trimmed).find()) t = i;
                if (c < 0 && COLUMN_HDR.matcher(trimmed).find()) c = i;
            }
            if (t >= 0 && c >= 0) {
                headerRow = r;
                tableIdx = t;
                colIdx = c;
                break;
            }
        }
        if (headerRow < 0) return;
        for (int r = headerRow + 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String table = cellString(row, tableIdx);
            String column = cellString(row, colIdx);
            if (table == null || column == null) continue;
            tables.computeIfAbsent(table.trim().toLowerCase(Locale.ROOT),
                    k -> new ArrayList<>()).add(column.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static String cellString(Row row, int col) {
        if (col < 0) return null;
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        String v = cell.getCellType() == CellType.STRING ? cell.getStringCellValue() : null;
        return v == null || v.isBlank() ? null : v;
    }
}
