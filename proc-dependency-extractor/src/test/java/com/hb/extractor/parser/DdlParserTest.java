package com.hb.extractor.parser;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DdlParserTest {

    @Autowired
    private DdlParser ddlParser;

    @Test
    void testParseExistingDdl() {
        // c_agt_deposit_acct_inf_ddl.TXT 已存在于 jiaoben 目录
        List<String> fields = ddlParser.parse("C_AGT_DEPOSIT_ACCT_INF");
        assertNotNull(fields);
        assertFalse(fields.isEmpty());
        // DDL 第一个字段是 data_dt
        assertEquals("data_dt", fields.get(0));
        // 第二个字段是 acct_no
        assertEquals("acct_no", fields.get(1));
    }

    @Test
    void testParseNonExistingDdlReturnsNull() {
        List<String> fields = ddlParser.parse("TABLE_NOT_EXIST_999");
        assertNull(fields);
    }

    @Test
    void testParseInlineDdl() {
        String ddl = "CREATE TABLE \"my_table\" (\n" +
                "  \"col_a\" varchar(100) NOT NULL,\n" +
                "  \"col_b\" date DEFAULT NULL,\n" +
                "  \"col_c\" int DEFAULT NULL\n" +
                ")";
        List<String> fields = ddlParser.parseInline(ddl);
        assertEquals(3, fields.size());
        assertEquals("col_a", fields.get(0));
        assertEquals("col_b", fields.get(1));
        assertEquals("col_c", fields.get(2));
    }
}
