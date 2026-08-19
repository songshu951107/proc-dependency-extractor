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
}
