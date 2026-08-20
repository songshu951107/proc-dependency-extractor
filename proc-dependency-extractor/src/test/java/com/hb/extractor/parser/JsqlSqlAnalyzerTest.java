package com.hb.extractor.parser;

import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JsqlSqlAnalyzerTest {

    private JsqlSqlAnalyzer createAnalyzer() throws Exception {
        JsqlSqlAnalyzer analyzer = new JsqlSqlAnalyzer();
        // 反射注入 SqlPlaceholderReplacer
        var replacer = new SqlPlaceholderReplacer();
        var field = JsqlSqlAnalyzer.class.getDeclaredField("placeholderReplacer");
        field.setAccessible(true);
        field.set(analyzer, replacer);
        return analyzer;
    }

    @Test
    void testExtractConditionFields_JoinOn() throws Exception {
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO cdm.target_tbl SELECT t1.a, t2.b FROM tab1 t1 LEFT JOIN tab2 t2 ON t1.a = t2.b";
        Statement stmt = analyzer.parse(sql);
        assertNotNull(stmt);
        assertTrue(stmt instanceof net.sf.jsqlparser.statement.insert.Insert);
        var insert = (net.sf.jsqlparser.statement.insert.Insert) stmt;
        var select = insert.getSelect();
        assertTrue(select.getSelectBody() instanceof PlainSelect);
        var plainSelect = (PlainSelect) select.getSelectBody();

        List<JsqlSqlAnalyzer.ColumnRef> conditionFields = analyzer.extractConditionFields(plainSelect);

        assertFalse(conditionFields.isEmpty(), "应提取到 ON 条件字段");
        // ON t1.a = t2.b → 两个条件字段
        boolean hasT1A = conditionFields.stream().anyMatch(r -> "T1".equals(r.alias) && "a".equals(r.field));
        boolean hasT2B = conditionFields.stream().anyMatch(r -> "T2".equals(r.alias) && "b".equals(r.field));
        assertTrue(hasT1A, "应包含 T1.a");
        assertTrue(hasT2B, "应包含 T2.b");
        // role 应为 join_condition
        assertTrue(conditionFields.stream().allMatch(r -> "join_condition".equals(r.role)),
            "ON 条件字段 role 应为 join_condition");
    }

    @Test
    void testExtractConditionFields_Where() throws Exception {
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO cdm.target_tbl SELECT t1.a FROM tab1 t1 WHERE t1.a > 100";
        Statement stmt = analyzer.parse(sql);
        var insert = (net.sf.jsqlparser.statement.insert.Insert) stmt;
        var plainSelect = (PlainSelect) insert.getSelect().getSelectBody();

        List<JsqlSqlAnalyzer.ColumnRef> conditionFields = analyzer.extractConditionFields(plainSelect);

        assertFalse(conditionFields.isEmpty());
        boolean hasT1A = conditionFields.stream().anyMatch(r -> "T1".equals(r.alias) && "a".equals(r.field));
        assertTrue(hasT1A);
        assertTrue(conditionFields.stream().allMatch(r -> "where_condition".equals(r.role)));
    }

    @Test
    void testExtractConditionFields_Mixed() throws Exception {
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO cdm.target_tbl SELECT t1.a FROM tab1 t1 LEFT JOIN tab2 t2 ON t1.a = t2.b WHERE t1.a > 100";
        Statement stmt = analyzer.parse(sql);
        var plainSelect = (PlainSelect) ((net.sf.jsqlparser.statement.insert.Insert) stmt).getSelect().getSelectBody();

        List<JsqlSqlAnalyzer.ColumnRef> conditionFields = analyzer.extractConditionFields(plainSelect);

        long joinCount = conditionFields.stream().filter(r -> "join_condition".equals(r.role)).count();
        long whereCount = conditionFields.stream().filter(r -> "where_condition".equals(r.role)).count();
        assertTrue(joinCount >= 2, "ON 条件字段至少 2 个");
        assertTrue(whereCount >= 1, "WHERE 条件字段至少 1 个");
    }

    @Test
    void testExtractConditionFields_None() throws Exception {
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO cdm.target_tbl SELECT t1.a FROM tab1 t1";
        Statement stmt = analyzer.parse(sql);
        var plainSelect = (PlainSelect) ((net.sf.jsqlparser.statement.insert.Insert) stmt).getSelect().getSelectBody();

        List<JsqlSqlAnalyzer.ColumnRef> conditionFields = analyzer.extractConditionFields(plainSelect);

        assertTrue(conditionFields.isEmpty(), "无 JOIN/WHERE 时应返回空列表");
    }

    // ===== 修复 A: 无别名表的隐式 alias 登记 =====

    private PlainSelect parseInsertSelect(JsqlSqlAnalyzer analyzer, String sql) {
        Statement stmt = analyzer.parse(sql);
        assertNotNull(stmt, "SQL 应解析成功");
        var insert = (net.sf.jsqlparser.statement.insert.Insert) stmt;
        var select = insert.getSelect();
        assertNotNull(select);
        assertTrue(select.getSelectBody() instanceof PlainSelect, "SELECT 体应为 PlainSelect");
        return (PlainSelect) select.getSelectBody();
    }

    @Test
    void testExtractAliasMap_ImplicitTableAlias() throws Exception {
        // 修复 A: FROM vt_x 无别名 → 表名自身即隐式引用前缀 VT_X → vt_x
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        PlainSelect select = parseInsertSelect(analyzer,
                "INSERT INTO cdm.t SELECT a FROM vt_x");
        java.util.Map<String, String> aliasMap = analyzer.extractAliasMap(select);
        assertEquals(1, aliasMap.size(), "无别名单表应登记隐式 alias");
        assertEquals("vt_x", aliasMap.get("VT_X"));
    }

    @Test
    void testExtractAliasMap_ExplicitAliasUnchanged() throws Exception {
        // 显式别名行为不变: 只有 T → vt_x，不额外登记 VT_X
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        PlainSelect select = parseInsertSelect(analyzer,
                "INSERT INTO cdm.t SELECT t.a FROM vt_x t");
        java.util.Map<String, String> aliasMap = analyzer.extractAliasMap(select);
        assertEquals(1, aliasMap.size());
        assertEquals("vt_x", aliasMap.get("T"));
        assertFalse(aliasMap.containsKey("VT_X"), "显式别名时表名不应再作为隐式前缀登记");
    }

    @Test
    void testExtractAliasMap_TwoTablesWithoutAlias() throws Exception {
        // 多表无别名: 各自登记隐式 alias，size=2（兜底歧义行为不变）
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        PlainSelect select = parseInsertSelect(analyzer,
                "INSERT INTO cdm.t SELECT x.a, y.b FROM vt_x JOIN vt_y ON x.id = y.id");
        java.util.Map<String, String> aliasMap = analyzer.extractAliasMap(select);
        assertEquals(2, aliasMap.size());
        assertEquals("vt_x", aliasMap.get("VT_X"));
        assertEquals("vt_y", aliasMap.get("VT_Y"));
    }

    // ===== 修复 B: UNION ALL 子查询源表提取 =====

    @Test
    void testExtractTablesRecursively_UnionAllSubquery() throws Exception {
        // dwa_c_agt_ldr_acct_intst block 4 结构: FROM (SELECT...UNION ALL SELECT...) TAA
        JsqlSqlAnalyzer analyzer = createAnalyzer();
        String sql = "INSERT INTO {CDM}.C_AGT_LDR_ACCT_INTST " +
                "SELECT TAA.acct_no, SUM(day_intst) AS day_intst " +
                "FROM (SELECT TLX.acct_no, day_intst AS day_intst " +
                "      FROM {CDM}.VT_C_AGT_LDR_ACCT_INTST_01 TLX " +
                "      UNION ALL " +
                "      SELECT TLD.acct_no, TLD.month_intst AS day_intst " +
                "      FROM {CDM}.C_AGT_LDR_ACCT_INTST TLD WHERE data_dt = DATE '{I_LAST_DT}') TAA " +
                "GROUP BY TAA.acct_no";
        PlainSelect select = parseInsertSelect(analyzer, sql);
        List<JsqlSqlAnalyzer.TableRefInfo> tables = analyzer.extractTablesRecursively(select, 0);
        assertTrue(tables.stream().anyMatch(t -> "vt_c_agt_ldr_acct_intst_01".equals(t.tableName)),
                "应提取 UNION 分支 1 的 VT_C_AGT_LDR_ACCT_INTST_01, 实际: " + tables);
        assertTrue(tables.stream().anyMatch(t -> "c_agt_ldr_acct_intst".equals(t.tableName)),
                "应提取 UNION 分支 2 的 C_AGT_LDR_ACCT_INTST, 实际: " + tables);
        assertTrue(tables.stream().anyMatch(t -> t.isSubquery && "TAA".equals(t.alias)),
                "子查询 alias TAA 应保留占位记录");
    }
}
