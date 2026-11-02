package com.hb.extractor.parser;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableStructureExcelParserTest {

    private static ByteArrayInputStream xlsx(String[] header,
                                             String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("columns");
            Row h = sheet.createRow(0);
            for (int i = 0; i < header.length; i++) h.createCell(i).setCellValue(header[i]);
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    @Test
    void convertsChineseHeaderToDdl() throws Exception {
        String ddl = TableStructureExcelParser.toDdl(xlsx(
                new String[]{"表名", "列名", "数据类型"},
                new String[][]{
                        {"F_X", "cust_no", "VARCHAR"},
                        {"F_X", "org_type_cd", "VARCHAR"},
                        {"O_X", "cust_id", "VARCHAR"},
                }));
        // 生成合成 DDL 后可被既有管线解析
        assertEquals(List.of("F_X", "O_X"),
                TableMappingDeriver.extractCreateTableNames(ddl));
        assertEquals(List.of("cust_no", "org_type_cd"),
                TableMappingDeriver.parseDdlColumns(ddl, "F_X"));
        assertEquals(List.of("cust_id"),
                TableMappingDeriver.parseDdlColumns(ddl, "O_X"));
    }

    @Test
    void convertsEnglishHeaderWithSchemaTable() throws Exception {
        String ddl = TableStructureExcelParser.toDdl(xlsx(
                new String[]{"TABLE_SCHEMA", "TABLE_NAME", "COLUMN_NAME"},
                new String[][]{
                        {"ODM", "O_Y", "C1"},
                        {"ODM", "O_Y", "C2"},
                }));
        assertEquals(List.of("O_Y"),
                TableMappingDeriver.extractCreateTableNames(ddl));
        assertEquals(List.of("c1", "c2"), TableMappingDeriver.parseDdlColumns(ddl, "O_Y"));
    }

    @Test
    void rejectsExcelWithoutHeader() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("s1");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("some data");
            row.createCell(1).setCellValue("no header");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            assertThrows(IllegalArgumentException.class,
                    () -> TableStructureExcelParser.toDdl(
                            new ByteArrayInputStream(out.toByteArray())));
        }
    }
}
