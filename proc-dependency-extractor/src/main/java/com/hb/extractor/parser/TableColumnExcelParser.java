package com.hb.extractor.parser;

import com.hb.extractor.entity.TableColumnMeta;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.util.IOUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物理表字段元数据 Excel 解析器
 *
 * 输入：information_schema.columns 风格导出的 xlsx，首行表头，列顺序：
 *   TABLE_CATALOG, TABLE_VC, TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME,
 *   ORDINAL_POSITION, COLUMN_DEFAULT, IS_NULLABLE, DATA_TYPE,
 *   CHARACTER_MAXIMUM_LENGTH, CHARACTER_OCTET_LENGTH, NUMERIC_PRECISION,
 *   NUMERIC_SCALE, CHARACTER_SET_NAME, COLLATION_NAME, COLUMN_TYPE,
 *   COLUMN_KEY, EXTRA, PRIVILEGES, COLUMN_COMMENT, TABLE_NAME(重复)
 *
 * 输出：按 (schema, table) 分组的字段列表
 */
@Slf4j
@Component
public class TableColumnExcelParser {

    /**
     * 解析 xlsx 文件，按 (schema.table) 分组返回字段元数据。
     * 同一表内字段按 ORDINAL_POSITION 升序。
     */
    public Map<String, List<TableColumnMeta>> parse(Path xlsxPath) throws IOException {
        // columns0818.xlsx 含长字段注释（COLUMN_COMMENT），单 record 默认上限 100MB 会被触发，
        // 这里放宽到 256MB（POI 全局静态变量，JVM 范围生效）
        IOUtils.setByteArrayMaxOverride(256 * 1024 * 1024);
        Map<String, List<TableColumnMeta>> grouped = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(xlsxPath);
             Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();
            log.info("解析 xlsx: {}, 共 {} 行（含表头）", xlsxPath, lastRow + 1);

            for (int r = 1; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String schema = getString(row, 2);   // TABLE_SCHEMA
                String table = getString(row, 3);    // TABLE_NAME
                String column = getString(row, 4);   // COLUMN_NAME
                if (schema == null || table == null || column == null) continue;

                TableColumnMeta m = new TableColumnMeta();
                schema = schema.toLowerCase();
                table = table.toLowerCase();
                column = column.toLowerCase();
                m.setSchemaName(schema);
                m.setTableName(table);
                m.setFullTableName(schema + "." + table);
                m.setColumnName(column);
                m.setOrdinalPosition(getInt(row, 5));
                m.setColumnDefault(getString(row, 6));
                m.setIsNullable(getNullableString(row, 7, "NO"));
                m.setDataType(getString(row, 8));
                m.setCharacterMaximumLength(getInt(row, 9));
                // 跳过 CHARACTER_OCTET_LENGTH(10)
                m.setNumericPrecision(getInt(row, 11));
                m.setNumericScale(getInt(row, 12));
                m.setCharacterSetName(getString(row, 13));
                m.setCollationName(getString(row, 14));
                m.setColumnType(getString(row, 15));
                m.setColumnKey(getString(row, 16));
                m.setExtra(getString(row, 17));
                // 跳过 PRIVILEGES(18)
                m.setColumnComment(getString(row, 19));

                grouped.computeIfAbsent(m.getFullTableName(), k -> new ArrayList<>()).add(m);
            }
        }
        // 每张表内按 ordinal_position 排序
        for (List<TableColumnMeta> cols : grouped.values()) {
            cols.sort((a, b) -> {
                int ao = a.getOrdinalPosition() == null ? 0 : a.getOrdinalPosition();
                int bo = b.getOrdinalPosition() == null ? 0 : b.getOrdinalPosition();
                return Integer.compare(ao, bo);
            });
        }
        log.info("解析完成: 共 {} 张表", grouped.size());
        return grouped;
    }

    private String getString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        cell.setCellType(CellType.STRING);
        String v = cell.getStringCellValue();
        return v == null || v.isEmpty() ? null : v;
    }

    private String getNullableString(Row row, int col, String defaultVal) {
        String v = getString(row, col);
        return v == null ? defaultVal : v;
    }

    private Integer getInt(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        CellType t = cell.getCellType();
        if (t == CellType.NUMERIC) {
            return (int) cell.getNumericCellValue();
        }
        if (t == CellType.STRING) {
            String s = cell.getStringCellValue();
            if (s == null || s.isEmpty()) return null;
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
