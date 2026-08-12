package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.ColumnMapping;
import com.hb.extractor.parser.dto.TableRef;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ReportSqlParserTest {

    @Autowired
    private ReportSqlParser reportSqlParser;

    @Test
    void testExtractTables() {
        String sql = "select a.STAT_DT, a.CUST_ID from RPT.A_CFIN_DEPT_ACCT_INFO a " +
                "LEFT JOIN FDM.F_AC_CRBS_CUST_ACCT_NO b ON a.CUST_ACCT_NUM = b.CUST_ACCT_NO";
        List<TableRef> tables = reportSqlParser.extractTables(sql);

        assertEquals(2, tables.size());
        assertEquals("RPT", tables.get(0).getSchema());
        assertEquals("A_CFIN_DEPT_ACCT_INFO", tables.get(0).getTable());
        assertEquals("a", tables.get(0).getAlias());
        assertEquals("FDM", tables.get(1).getSchema());
        assertEquals("F_AC_CRBS_CUST_ACCT_NO", tables.get(1).getTable());
        assertEquals("b", tables.get(1).getAlias());
    }

    @Test
    void testSelectAliasAs() {
        String sql = "select trim(a.GL_NUM) as a2 from RPT.T a";
        List<ColumnMapping> mappings = reportSqlParser.extractColumns(sql);

        assertEquals(1, mappings.size());
        assertEquals("a2", mappings.get(0).getSelectAlias());
        assertEquals("GL_NUM", mappings.get(0).getSourceField());
        assertEquals("rpt.t", mappings.get(0).getSourceTable());
    }

    @Test
    void testSelectAliasSpace() {
        String sql = "select nvl(a.CURR_BAL,0) CURR_BAL from RPT.T a";
        List<ColumnMapping> mappings = reportSqlParser.extractColumns(sql);

        assertEquals(1, mappings.size());
        assertEquals("CURR_BAL", mappings.get(0).getSelectAlias());
        assertEquals("CURR_BAL", mappings.get(0).getSourceField());
    }

    @Test
    void testSelectAliasField() {
        String sql = "select a.STAT_DT from RPT.T a";
        List<ColumnMapping> mappings = reportSqlParser.extractColumns(sql);

        assertEquals(1, mappings.size());
        assertEquals("STAT_DT", mappings.get(0).getSelectAlias());
        assertEquals("a.STAT_DT", mappings.get(0).getSourceExpr());
        assertEquals("STAT_DT", mappings.get(0).getSourceField());
        assertEquals("rpt.t", mappings.get(0).getSourceTable());
    }

    @Test
    void testParamPlaceholder() {
        String sql = "select a.STAT_DT from RPT.T a where a.STAT_DT = date'${data_dt}' " +
                "${if(len(CUST_ID)==0,\"\",\"and a.CUST_ID in ('\"+replace(CUST_ID,\",\",\",'\")+\"')\")}";
        List<TableRef> tables = reportSqlParser.extractTables(sql);
        List<ColumnMapping> mappings = reportSqlParser.extractColumns(sql);

        assertEquals(1, tables.size());
        assertEquals(1, mappings.size());
        assertEquals("STAT_DT", mappings.get(0).getSelectAlias());
    }

    @Test
    void testCommentStripping() {
        String sql = "select a.STAT_DT -- 数据日期\n, a.CUST_ID /* 客户号 */ from RPT.T a";
        List<ColumnMapping> mappings = reportSqlParser.extractColumns(sql);

        assertEquals(2, mappings.size());
        assertEquals("STAT_DT", mappings.get(0).getSelectAlias());
        assertEquals("CUST_ID", mappings.get(1).getSelectAlias());
    }

    @Test
    void testTableWithoutSchema() {
        // 无 schema 前缀、无别名：from A_CUST_SORT_BL
        String sql = "select CORE_CUST_NUM, CUST_NM, SORT from A_CUST_SORT_BL";
        List<TableRef> tables = reportSqlParser.extractTables(sql);

        assertEquals(1, tables.size());
        assertNull(tables.get(0).getSchema());
        assertEquals("A_CUST_SORT_BL", tables.get(0).getTable());
        assertNull(tables.get(0).getAlias());
        assertEquals("a_cust_sort_bl", tables.get(0).getFullName());
    }

    @Test
    void testColumnWithoutAliasFieldSingleTable() {
        // 单表无 schema、字段无 alias.field：应兜底归属到唯一表
        String sql = "select CORE_CUST_NUM, CUST_NM, SORT from A_CUST_SORT_BL";
        List<ColumnMapping> mappings = reportSqlParser.extractColumns(sql);

        assertEquals(3, mappings.size());
        assertEquals("CORE_CUST_NUM", mappings.get(0).getSelectAlias());
        assertEquals("CORE_CUST_NUM", mappings.get(0).getSourceField());
        assertEquals("a_cust_sort_bl", mappings.get(0).getSourceTable());
        assertEquals("SORT", mappings.get(2).getSelectAlias());
        assertEquals("a_cust_sort_bl", mappings.get(2).getSourceTable());
    }

    @Test
    void testTableWithoutSchemaFollowedByKeyword() {
        // from t where 不应把 where 误识别为别名
        String sql = "select a from T where a = 1";
        List<TableRef> tables = reportSqlParser.extractTables(sql);
        assertEquals(1, tables.size());
        assertNull(tables.get(0).getAlias());
    }
}
