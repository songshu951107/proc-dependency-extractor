package com.hb.extractor.parser;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TableMappingDeriverTest {

    // ------------------------------------------------------------ DDL

    @Test
    void ddlColumnsBacktickAndConstraints() {
        String ddl = """
                CREATE TABLE `FDM`.`F_T` (
                  `cust_no` VARCHAR(32) NOT NULL COMMENT '客户号',
                  `amt` DECIMAL(18,2),
                  PRIMARY KEY (`cust_no`),
                  KEY `idx_amt` (`amt`)
                );
                """;
        List<String> cols = TableMappingDeriver.parseDdlColumns(ddl);
        assertEquals(List.of("cust_no", "amt"), cols);
    }

    @Test
    void ddlColumnsPlainAndDoubleQuote() {
        String ddl = """
                CREATE TABLE O_T (
                  "CUST_ID" VARCHAR(32),
                  org_type  VARCHAR(10), -- 行注释
                  /* 块注释 del_flag CHAR(1), */
                  del_ind CHAR(1)
                );
                """;
        List<String> cols = TableMappingDeriver.parseDdlColumns(ddl);
        assertEquals(List.of("cust_id", "org_type", "del_ind"), cols);
    }

    // -------------------------------------------------------- 加工脚本

    @Test
    void scriptExplicitColumnList() {
        String script = """
                -- 目标表加工
                INSERT INTO {FDM}.F_T
                  (cust_no, amt, del_flag)
                SELECT
                  T1.cust_id,          -- 客户号
                  T1.amt + T1.fee,     -- 表达式
                  'N'
                FROM {TDM}.T_CUST T1
                WHERE T1.dt = DATE'{TX_DATE}';
                """;
        TableMappingDeriver.ScriptMapping m =
                TableMappingDeriver.parseScript(script, "F_T", null);
        assertEquals(3, m.cols.size());
        assertEquals("cust_no", m.cols.get(0).targetCol);
        assertEquals("cust_id", m.cols.get(0).sourceCol);
        assertNull(m.cols.get(1).sourceCol);           // 表达式未匹配
        assertEquals("del_flag", m.cols.get(2).targetCol);
        assertNull(m.cols.get(2).sourceCol);
    }

    @Test
    void scriptDdlOrderAlignmentWhenNoColumnList() {
        String script = """
                INSERT INTO ODM.O_T
                SELECT cust_id, org_type
                  FROM TDM.T_CUST;
                """;
        TableMappingDeriver.ScriptMapping m = TableMappingDeriver.parseScript(
                script, "O_T", List.of("cust_id", "org_type", "del_ind"));
        assertEquals("cust_id", m.cols.get(0).targetCol);
        assertEquals("cust_id", m.cols.get(0).sourceCol);
        assertEquals("org_type", m.cols.get(1).targetCol);
        assertEquals("org_type", m.cols.get(1).sourceCol);
    }

    @Test
    void scriptExpressionWithAlias() {
        String script = """
                INSERT INTO F_T
                  (zip_cd)
                SELECT SUBSTR(T1.zone, 1, 2) AS zip_cd
                  FROM T_ZIP T1;
                """;
        TableMappingDeriver.ScriptMapping m =
                TableMappingDeriver.parseScript(script, "F_T", null);
        assertEquals("zip_cd", m.cols.get(0).targetCol);
        assertNull(m.cols.get(0).sourceCol);
        assertNotNull(m.cols.get(0).reason);
    }

    @Test
    void scriptIgnoresOtherInsertTargets() {
        String script = """
                INSERT INTO OTHER_T (a) SELECT x FROM T1;
                INSERT INTO F_T (cust_no) SELECT T1.cust_id FROM T_CUST T1;
                """;
        TableMappingDeriver.ScriptMapping m =
                TableMappingDeriver.parseScript(script, "F_T", null);
        assertEquals(1, m.cols.size());
        assertEquals("cust_id", m.cols.get(0).sourceCol);
    }

    // ---------------------------------------------------------- 交集推导

    @Test
    void deriveIntersectsOnTColumns() {
        String fScript = """
                INSERT INTO FDM.F_T (cust_no, org_type_cd, rmk)
                SELECT T.cust_id, T.org_type, T.note FROM T_CUST T;
                """;
        String oScript = """
                INSERT INTO ODM.O_T (cust_id, org_cust_type, remarks)
                SELECT T.cust_id, T.org_type, T.note FROM T_CUST T;
                """;
        TableMappingDeriver.ScriptMapping f =
                TableMappingDeriver.parseScript(fScript, "F_T", null);
        TableMappingDeriver.ScriptMapping o =
                TableMappingDeriver.parseScript(oScript, "O_T", null);
        TableMappingDeriver.DeriveResult r = TableMappingDeriver.derive(f, o);

        assertEquals(3, r.matchedCount);
        assertEquals("cust_id", r.columnMapping.get("cust_no"));
        assertEquals("org_cust_type", r.columnMapping.get("org_type_cd"));
        assertEquals("remarks", r.columnMapping.get("rmk"));
        assertTrue(r.unmatchedF.isEmpty());
        assertTrue(r.unmatchedO.isEmpty());
    }

    @Test
    void deriveReportsOneSideUnmatched() {
        String fScript = """
                INSERT INTO F_T (a, b) SELECT T.x, T.y FROM T T;
                """;
        String oScript = """
                INSERT INTO O_T (c) SELECT T.x FROM T T;
                """;
        TableMappingDeriver.DeriveResult r = TableMappingDeriver.derive(
                TableMappingDeriver.parseScript(fScript, "F_T", null),
                TableMappingDeriver.parseScript(oScript, "O_T", null));
        assertEquals(1, r.matchedCount);
        assertEquals("c", r.columnMapping.get("a"));
        // F 侧的 T.y 在 O 侧没有对应 → F 列 b 进 unmatchedF
        assertEquals(1, r.unmatchedF.size());
        assertEquals("b", r.unmatchedF.get(0).get("target_col"));
        assertTrue(r.unmatchedF.get(0).get("reason").toString().contains("y"));
        assertTrue(r.unmatchedO.isEmpty());
    }

    // ------------------------------------------------------ 字符串字面量

    @Test
    void selectListWithCommaInString() {
        String script = """
                INSERT INTO F_T (a, b)
                SELECT T.x, 'a,b,c'
                  FROM T T;
                """;
        TableMappingDeriver.ScriptMapping m =
                TableMappingDeriver.parseScript(script, "F_T", null);
        assertEquals(2, m.cols.size());
        assertEquals("x", m.cols.get(0).sourceCol);
        assertEquals("b", m.cols.get(1).targetCol);
        assertNull(m.cols.get(1).sourceCol);   // 字面量整体归为表达式
    }

    @Test
    void noMatchReturnsEmpty() {
        TableMappingDeriver.ScriptMapping m =
                TableMappingDeriver.parseScript("SELECT 1", "F_T", null);
        assertTrue(m.cols.isEmpty());
        List<String> cols = TableMappingDeriver.parseDdlColumns("not a ddl");
        assertTrue(cols.isEmpty());
    }

    @Test
    void unmatchedMapShape() {
        String fScript = "INSERT INTO F_T (a) SELECT CASE WHEN T.x>1 THEN 1 ELSE 2 END FROM T T;";
        TableMappingDeriver.ScriptMapping f =
                TableMappingDeriver.parseScript(fScript, "F_T", null);
        assertEquals(1, f.cols.size());
        Map<String, Object> u = TableMappingDeriver.derive(f,
                TableMappingDeriver.parseScript("INSERT INTO O_T (b) SELECT T.x FROM T T;", "O_T", null))
                .unmatchedF.get(0);
        assertEquals("a", u.get("target_col"));
        assertTrue(u.get("expr").toString().contains("CASE"));
    }
}
