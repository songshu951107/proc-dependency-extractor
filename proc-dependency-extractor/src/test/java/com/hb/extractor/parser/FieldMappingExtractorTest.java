package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.FieldMapping;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class FieldMappingExtractorTest {

    @Autowired
    private FieldMappingExtractor extractor;

    private Map<String, String> aliasMap() {
        // alias -> tableShort
        Map<String, String> m = new HashMap<>();
        m.put("T01", "F_AC_CRBS_DEPOSIT_ACCT");
        m.put("TCA", "F_AC_CRBS_CUST_ACCT_NO_COMPAR_RELA");
        return m;
    }

    @Test
    void testSimpleFieldWithDdl() {
        String sql = "INSERT INTO C_AGT_DEPOSIT_ACCT_INF \n" +
                "SELECT T01.acct_no, T01.cust_name, DATE '{ETL_TX_DT}' AS data_dt \n" +
                "FROM F_AC_CRBS_DEPOSIT_ACCT T01";
        List<String> ddlFields = Arrays.asList("data_dt", "acct_no", "cust_name");
        List<FieldMapping> result = extractor.extract(sql, "C_AGT_DEPOSIT_ACCT_INF", aliasMap(), ddlFields);
        assertEquals(3, result.size());
        // 按 DDL 顺序对应：第1列 data_dt ← DATE '{ETL_TX_DT}'
        assertEquals("data_dt", result.get(0).getTargetField());
        assertEquals("acct_no", result.get(1).getTargetField());
        assertEquals("cust_name", result.get(2).getTargetField());
        // 第2列 acct_no ← T01.acct_no，来源表 F_AC_CRBS_DEPOSIT_ACCT
        assertEquals("T01.acct_no", result.get(1).getSourceExpr());
        assertEquals("T01", result.get(1).getSourceAlias());
        assertEquals("F_AC_CRBS_DEPOSIT_ACCT", result.get(1).getSourceTableShort());
    }

    @Test
    void testTempTableWithAsAlias() {
        String sql = "CREATE TEMPORARY TABLE VT_X \n" +
                "SELECT T01.liabi_acct_no AS acct_no, T01.cust_id AS cust_no, CASE WHEN T01.amt > 0 THEN 'Y' ELSE 'N' END AS flag \n" +
                "FROM F_AC_CRBS_DEPOSIT_ACCT T01";
        List<FieldMapping> result = extractor.extract(sql, "VT_X", aliasMap(), null);
        assertEquals(3, result.size());
        // 临时表无 DDL，从 AS alias 推断 target_field
        assertEquals("acct_no", result.get(0).getTargetField());
        assertEquals("cust_no", result.get(1).getTargetField());
        assertEquals("flag", result.get(2).getTargetField());
        // 来源表
        assertEquals("F_AC_CRBS_DEPOSIT_ACCT", result.get(0).getSourceTableShort());
    }

    @Test
    void testCaseWhenExpression() {
        String sql = "INSERT INTO T \n" +
                "SELECT CASE WHEN T01.amt > 0 THEN 'Y' ELSE 'N' END AS biz_type, T01.id \n" +
                "FROM F_AC_CRBS_DEPOSIT_ACCT T01";
        List<String> ddlFields = Arrays.asList("biz_type", "id");
        List<FieldMapping> result = extractor.extract(sql, "T", aliasMap(), ddlFields);
        assertEquals(2, result.size());
        assertEquals("biz_type", result.get(0).getTargetField());
        assertTrue(result.get(0).getSourceExpr().contains("CASE WHEN"));
        assertEquals("F_AC_CRBS_DEPOSIT_ACCT", result.get(0).getSourceTableShort());
    }

    @Test
    void testColumnCountMismatch() {
        // DDL 5 个字段，SELECT 3 列
        String sql = "INSERT INTO T \n" +
                "SELECT T01.a, T01.b, T01.c \n" +
                "FROM F_AC_CRBS_DEPOSIT_ACCT T01";
        List<String> ddlFields = Arrays.asList("a", "b", "c", "d", "e");
        List<FieldMapping> result = extractor.extract(sql, "T", aliasMap(), ddlFields);
        assertEquals(3, result.size());
        assertEquals("a", result.get(0).getTargetField());
        assertEquals("c", result.get(2).getTargetField());
    }

    @Test
    void testNoAliasConstant() {
        String sql = "INSERT INTO T \n" +
                "SELECT 'CONSTANT', T01.id \n" +
                "FROM F_AC_CRBS_DEPOSIT_ACCT T01";
        List<String> ddlFields = Arrays.asList("col1", "col2");
        List<FieldMapping> result = extractor.extract(sql, "T", aliasMap(), ddlFields);
        assertEquals(2, result.size());
        assertEquals("col1", result.get(0).getTargetField());
        assertEquals("'CONSTANT'", result.get(0).getSourceExpr());
        assertNull(result.get(0).getSourceTableShort());
    }
}
