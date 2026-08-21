package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.ConditionField;
import com.hb.extractor.parser.dto.FieldMapping;
import com.hb.extractor.parser.dto.TransformInput;
import net.sf.jsqlparser.statement.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class FieldMappingExtractorTest {

    private FieldMappingExtractor extractor;
    private JsqlSqlAnalyzer analyzer;
    private SqlPlaceholderReplacer placeholderReplacer;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // MyBatis-Plus 3.5.7 LambdaQueryWrapper 列解析依赖 TableInfo lambda cache（生产由 Spring 启动初始化）；
        // 单测环境需手动初始化，否则 getSqlSegment() 抛 "can not find lambda cache for this entity"
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.hb.extractor.entity.TableColumnMeta.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        placeholderReplacer = new SqlPlaceholderReplacer();
        analyzer = new JsqlSqlAnalyzer();
        // JsqlSqlAnalyzer 的 @Autowired placeholderReplacer 字段需手动注入
        setField(analyzer, "placeholderReplacer", placeholderReplacer);

        extractor = new FieldMappingExtractor();
        // FieldMappingExtractor 的 @Autowired analyzer / placeholderReplacer 字段需手动注入
        setField(extractor, "analyzer", analyzer);
        setField(extractor, "placeholderReplacer", placeholderReplacer);
    }

    /** 反射注入 private 字段（@Autowired 字段在单元测试中需手动注入） */
    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = null;
        Class<?> clazz = target.getClass();
        while (clazz != null && f == null) {
            try {
                f = clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        if (f == null) throw new IllegalStateException("字段未找到: " + fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Map<String, String> aliasMap() {
        // alias -> tableShort
        Map<String, String> m = new HashMap<>();
        m.put("T01", "F_AC_CRBS_DEPOSIT_ACCT");
        m.put("TCA", "F_AC_CRBS_CUST_ACCT_NO_COMPAR_RELA");
        return m;
    }

    /** 创建已注入 analyzer / placeholderReplacer 的 extractor（tableColumnMetaMapper 不注入，保持为 null） */
    private FieldMappingExtractor createExtractor() {
        return extractor;
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
        // JsqlParser 4.9 要求 CREATE TABLE AS SELECT 标准语法（GBase 的无 AS 方言需 preprocess 补齐）
        String sql = "CREATE TEMPORARY TABLE VT_X AS \n" +
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

    // ===== classifyLogicType 测试 =====

    @Test
    void testClassifyLogicType_Literal() {
        assertEquals("LITERAL", extractor.classifyLogicType("'N'"));
        assertEquals("LITERAL", extractor.classifyLogicType("123"));
        assertEquals("LITERAL", extractor.classifyLogicType("DATE '{ETL_TX_DT}'"));
        assertEquals("LITERAL", extractor.classifyLogicType("{ETL_TX_DT}"));
    }

    @Test
    void testClassifyLogicType_Case() {
        assertEquals("CASE", extractor.classifyLogicType("CASE WHEN T01.flag='1' THEN B.amt ELSE C.amt END"));
    }

    @Test
    void testClassifyLogicType_Aggregates() {
        assertEquals("SUM", extractor.classifyLogicType("SUM(T01.amt)"));
        assertEquals("COUNT", extractor.classifyLogicType("COUNT(DISTINCT T01.acct_no)"));
        assertEquals("AVG", extractor.classifyLogicType("AVG(T01.amt)"));
        assertEquals("MAX", extractor.classifyLogicType("MAX(T01.amt)"));
        assertEquals("MIN", extractor.classifyLogicType("MIN(T01.amt)"));
    }

    @Test
    void testClassifyLogicType_Nvl() {
        assertEquals("NVL", extractor.classifyLogicType("NVL(T01.a, T02.b)"));
        assertEquals("COALESCE", extractor.classifyLogicType("COALESCE(T01.a, T02.b, T03.c)"));
    }

    @Test
    void testClassifyLogicType_Concat() {
        assertEquals("CONCAT", extractor.classifyLogicType("T01.prefix || T01.code"));
        assertEquals("CONCAT", extractor.classifyLogicType("CONCAT(T01.prefix, T01.code)"));
    }

    @Test
    void testClassifyLogicType_Arithmetic() {
        assertEquals("ADDITION", extractor.classifyLogicType("T01.amt + T02.fee"));
        assertEquals("SUBTRACTION", extractor.classifyLogicType("T01.amt - T02.fee"));
        assertEquals("MULTIPLICATION", extractor.classifyLogicType("T01.rate * T01.amt"));
        assertEquals("DIVISION", extractor.classifyLogicType("T01.amt / T01.days"));
    }

    @Test
    void testClassifyLogicType_MixedArithmetic() {
        // AST 顶层运算符（同优先级左结合：(a+b)-c 顶层为 Subtraction，(a-b)+c 顶层为 Addition）
        assertEquals("SUBTRACTION", extractor.classifyLogicType("T01.amt + T02.fee - T03.tax"));
        assertEquals("ADDITION", extractor.classifyLogicType("T01.amt - T02.fee + T03.tax"));
    }

    @Test
    void testClassifyLogicType_Priority() {
        // 混合运算:外层运算符优先于内层聚合
        assertEquals("ADDITION", extractor.classifyLogicType("SUM(T01.amt) + T02.fee"));
        // 括号内运算符不影响外层分类
        assertEquals("SUM", extractor.classifyLogicType("SUM(T01.amt + T02.fee)"));
    }

    @Test
    void testClassifyLogicType_FunctionOther() {
        assertEquals("FUNCTION_OTHER", extractor.classifyLogicType("SUBSTR(T01.code, 1, 4)"));
    }

    @Test
    void testClassifyLogicType_Direct() {
        assertEquals("DIRECT", extractor.classifyLogicType("T01.acct_no"));
        assertEquals("DIRECT", extractor.classifyLogicType("acct_no"));
    }

    @Test
    void testClassifyLogicType_Null() {
        assertEquals("DIRECT", extractor.classifyLogicType(null));
        assertEquals("DIRECT", extractor.classifyLogicType(""));
        assertEquals("DIRECT", extractor.classifyLogicType("  "));
    }

    // ===== extractInputs 测试 =====

    private Map<String, String> aliasMap3() {
        Map<String, String> m = new HashMap<>();
        m.put("T01", "F_AC_CRBS_DEPOSIT_ACCT");
        m.put("T02", "F_AC_CRBS_CUST_INFO");
        m.put("T03", "F_AC_CRBS_ACCT_BAL");
        return m;
    }

    @Test
    void testExtractInputs_Direct() {
        List<TransformInput> inputs = extractor.extractInputs("T01.acct_no", aliasMap3(), "F_AC_CRBS_DEPOSIT_ACCT");
        assertEquals(1, inputs.size());
        assertEquals("F_AC_CRBS_DEPOSIT_ACCT", inputs.get(0).getSourceTableShort());
        assertEquals("acct_no", inputs.get(0).getSourceField());
        assertEquals("T01", inputs.get(0).getSourceAlias());
        assertEquals("value", inputs.get(0).getRole());
        assertEquals(1, inputs.get(0).getInputOrder());
    }

    @Test
    void testExtractInputs_Addition() {
        List<TransformInput> inputs = extractor.extractInputs("T01.amt + T02.fee", aliasMap3(), null);
        assertEquals(2, inputs.size());
        assertEquals("T01", inputs.get(0).getSourceAlias());
        assertEquals("amt", inputs.get(0).getSourceField());
        assertEquals(1, inputs.get(0).getInputOrder());
        assertEquals("T02", inputs.get(1).getSourceAlias());
        assertEquals("fee", inputs.get(1).getSourceField());
        assertEquals(2, inputs.get(1).getInputOrder());
    }

    @Test
    void testExtractInputs_CaseWhen() {
        String expr = "CASE WHEN T01.flag='1' THEN T02.amt ELSE T03.amt END";
        List<TransformInput> inputs = extractor.extractInputs(expr, aliasMap3(), null);
        assertEquals(3, inputs.size());
        // flag 是条件
        assertEquals("condition", inputs.get(0).getRole());
        assertEquals("T01", inputs.get(0).getSourceAlias());
        assertEquals("flag", inputs.get(0).getSourceField());
        // amt 是取值
        assertEquals("value", inputs.get(1).getRole());
        assertEquals("T02", inputs.get(1).getSourceAlias());
        assertEquals("value", inputs.get(2).getRole());
        assertEquals("T03", inputs.get(2).getSourceAlias());
    }

    @Test
    void testExtractInputs_CaseWhenMultiBranch() {
        String expr = "CASE WHEN T01.x>0 AND T01.y<10 THEN T02.z WHEN T01.x<0 THEN T03.z ELSE T02.z END";
        List<TransformInput> inputs = extractor.extractInputs(expr, aliasMap3(), null);
        // 去重: T01.x, T01.y, T02.z, T03.z (T02.z 重复只保留首次)
        assertEquals(4, inputs.size());
        assertEquals("condition", inputs.get(0).getRole());
        assertEquals("x", inputs.get(0).getSourceField());
        assertEquals("condition", inputs.get(1).getRole());
        assertEquals("y", inputs.get(1).getSourceField());
        assertEquals("value", inputs.get(2).getRole());
        assertEquals("z", inputs.get(2).getSourceField());
        assertEquals("value", inputs.get(3).getRole());
        assertEquals("z", inputs.get(3).getSourceField());
    }

    @Test
    void testExtractInputs_Literal() {
        List<TransformInput> inputs = extractor.extractInputs("'N'", aliasMap3(), null);
        assertTrue(inputs.isEmpty());
    }

    @Test
    void testExtractInputs_SchemaVar() {
        List<TransformInput> inputs = extractor.extractInputs("DATE '{ETL_TX_DT}'", aliasMap3(), null);
        assertTrue(inputs.isEmpty());
    }

    @Test
    void testExtractInputs_NoAliasSingleSource() {
        // 无 alias 前缀,单源表推断
        List<TransformInput> inputs = extractor.extractInputs("acct_no", aliasMap3(), "F_AC_CRBS_DEPOSIT_ACCT");
        assertEquals(1, inputs.size());
        assertEquals("F_AC_CRBS_DEPOSIT_ACCT", inputs.get(0).getSourceTableShort());
        assertEquals("acct_no", inputs.get(0).getSourceField());
        assertNull(inputs.get(0).getSourceAlias());
    }

    @Test
    void testExtractInputs_AliasNotInMap() {
        // alias 未在 aliasMap 中,该输入 sourceTableShort=null
        List<TransformInput> inputs = extractor.extractInputs("T99.field1 + T01.field2", aliasMap3(), null);
        assertEquals(2, inputs.size());
        assertNull(inputs.get(0).getSourceTableShort());  // T99 未在 aliasMap
        assertEquals("F_AC_CRBS_DEPOSIT_ACCT", inputs.get(1).getSourceTableShort());
    }

    @Test
    void testExtractInputs_Sum() {
        List<TransformInput> inputs = extractor.extractInputs("SUM(T01.amt)", aliasMap3(), null);
        assertEquals(1, inputs.size());
        assertEquals("value", inputs.get(0).getRole());
        assertEquals("T01", inputs.get(0).getSourceAlias());
        assertEquals("amt", inputs.get(0).getSourceField());
    }

    @Test
    void testExtractInputs_Sum_SingleSource_NoFunctionNameLeak() {
        // 单源表场景：函数名 SUM 不应被当作独立字段提取
        // 回归 bug: SUM(T01.amt) 在 singleSourceTable 非空时曾误产出 sum 字段
        List<TransformInput> inputs = extractor.extractInputs("SUM(T01.amt)", aliasMap3(), "F_AC_CRBS_DEPOSIT_ACCT");
        assertEquals(1, inputs.size());
        assertEquals("amt", inputs.get(0).getSourceField());
        // 确保没有 sum 这个伪字段
        assertFalse(inputs.stream().anyMatch(i -> "sum".equalsIgnoreCase(i.getSourceField())));
    }

    @Test
    void testExtractInputs_StandAloneFieldInsideFunction_SingleSource() {
        // NVL(acct_no, 0) 单源表：acct_no 应提取，函数名 NVL 不应提取，字面量 0 不应提取
        List<TransformInput> inputs = extractor.extractInputs("NVL(acct_no, 0)", aliasMap3(), "F_AC_CRBS_DEPOSIT_ACCT");
        assertEquals(1, inputs.size());
        assertEquals("acct_no", inputs.get(0).getSourceField());
        assertNull(inputs.get(0).getSourceAlias());
    }

    @Test
    void testExtractInputs_Nvl() {
        List<TransformInput> inputs = extractor.extractInputs("NVL(T01.a, T02.b)", aliasMap3(), null);
        assertEquals(2, inputs.size());
        assertEquals("a", inputs.get(0).getSourceField());
        assertEquals("b", inputs.get(1).getSourceField());
    }

    @Test
    void testExtractInputs_ConcatOperator() {
        // CONCAT 运算符 || 拆分
        Map<String, String> aMap = new HashMap<>();
        aMap.put("T01", "F_AC_CRBS_DEPOSIT_ACCT");
        List<TransformInput> inputs = extractor.extractInputs("T01.prefix || T01.code", aMap, null);
        assertEquals(2, inputs.size());
        assertEquals("prefix", inputs.get(0).getSourceField());
        assertEquals("code", inputs.get(1).getSourceField());
        assertEquals("T01", inputs.get(0).getSourceAlias());
    }

    @Test
    void testExtractInputs_ConcatFunction() {
        // CONCAT 函数拆分
        Map<String, String> aMap = new HashMap<>();
        aMap.put("T01", "F_AC_CRBS_DEPOSIT_ACCT");
        List<TransformInput> inputs = extractor.extractInputs("CONCAT(T01.prefix, T01.code)", aMap, null);
        assertEquals(2, inputs.size());
        assertEquals("prefix", inputs.get(0).getSourceField());
        assertEquals("code", inputs.get(1).getSourceField());
    }

    @Test
    void testExtractInputs_MixedArithmetic() {
        // 混合运算多运算符拆分:所有字段都应被提取
        Map<String, String> aMap = new HashMap<>();
        aMap.put("T01", "F_AC_CRBS_DEPOSIT_ACCT");
        aMap.put("T02", "F_AC_CRBS_CUST_INFO");
        aMap.put("T03", "F_AC_CRBS_LOAN_ACCT");
        List<TransformInput> inputs = extractor.extractInputs("T01.a + T02.b - T03.c", aMap, null);
        assertEquals(3, inputs.size());
        assertEquals("a", inputs.get(0).getSourceField());
        assertEquals("b", inputs.get(1).getSourceField());
        assertEquals("c", inputs.get(2).getSourceField());
    }

    // ===== 集成测试 =====

    @Test
    void testExtractPopulatesInputsAndLogicType() {
        String sql = "INSERT INTO C_AGT_DEPOSIT_ACCT_INF \n" +
                "SELECT T01.acct_no, T01.amt + T02.fee AS total_fee, 'N' AS flag \n" +
                "FROM F_AC_CRBS_DEPOSIT_ACCT T01, F_AC_CRBS_CUST_INFO T02";
        Map<String, String> aMap = new HashMap<>();
        aMap.put("T01", "F_AC_CRBS_DEPOSIT_ACCT");
        aMap.put("T02", "F_AC_CRBS_CUST_INFO");
        List<String> ddlFields = Arrays.asList("acct_no", "total_fee", "flag");
        List<FieldMapping> result = extractor.extract(sql, "C_AGT_DEPOSIT_ACCT_INF", aMap, ddlFields);

        // 第1列: DIRECT
        assertEquals("DIRECT", result.get(0).getLogicType());
        assertEquals(1, result.get(0).getInputs().size());

        // 第2列: ADDITION, 2个输入
        assertEquals("ADDITION", result.get(1).getLogicType());
        assertEquals(2, result.get(1).getInputs().size());
        assertEquals("T01", result.get(1).getInputs().get(0).getSourceAlias());
        assertEquals("T02", result.get(1).getInputs().get(1).getSourceAlias());

        // 第3列: LITERAL, 0个输入
        assertEquals("LITERAL", result.get(2).getLogicType());
        assertTrue(result.get(2).getInputs().isEmpty());
    }

    // ===== ON/WHERE 条件字段（行血缘独立通道） =====

    @Test
    void testConditionFields_ExcludedFromValueInputs() {
        // 双通道重构后：ON/WHERE 条件字段不再附加到 FieldMapping.inputs（行血缘独立通道）
        String sql = "INSERT INTO cdm.target_tbl SELECT t1.a, t1.b FROM tab1 t1 LEFT JOIN tab2 t2 ON t1.a = t2.b WHERE t2.balance > 0";
        java.util.Map<String, String> aliasMap = new java.util.HashMap<>();
        aliasMap.put("T1", "tab1");
        aliasMap.put("T2", "tab2");

        List<FieldMapping> result = extractor.extract(
            sql, "target_tbl", aliasMap, java.util.Arrays.asList("a", "b"));

        assertEquals(2, result.size());
        for (FieldMapping fm : result) {
            // 值输入仅 SELECT 表达式自身字段，无任何 join_condition/where_condition
            assertTrue(fm.getInputs().stream().noneMatch(ti ->
                            "join_condition".equals(ti.getRole()) || "where_condition".equals(ti.getRole())),
                    "FieldMapping " + fm.getTargetField() + " 的值输入不应包含条件字段");
            assertEquals(1, fm.getInputs().size(), "t1.a / t1.b 各只有 1 个值输入");
        }
    }

    @Test
    void testExtractConditionFields_JoinAndWhereWithAttribution() throws Exception {
        String sql = "INSERT INTO cdm.target_tbl SELECT t1.a FROM tab1 t1 LEFT JOIN tab2 t2 ON t1.a = t2.b WHERE t2.balance > 0";
        Map<String, String> aliasMap = buildAliasMapByAnalyzer(sql);
        Statement stmt = analyzer.parse(sql);
        var ins = (net.sf.jsqlparser.statement.insert.Insert) stmt;
        var select = (net.sf.jsqlparser.statement.select.PlainSelect) ins.getSelect().getSelectBody();

        List<ConditionField> cfs = extractor.extractConditionFields(select, aliasMap);

        // t1.a / t2.b 为 join_condition，t2.balance 为 where_condition
        assertEquals(3, cfs.size());
        ConditionField t2b = cfs.stream()
                .filter(c -> "b".equals(c.getSourceField()) && "join_condition".equals(c.getRole()))
                .findFirst().orElseThrow();
        assertEquals("T2", t2b.getSourceAlias());
        assertEquals("tab2", t2b.getSourceTableShort());
        assertNotNull(t2b.getClause(), "join 条件应携带条件原文");

        ConditionField balance = cfs.stream()
                .filter(c -> "balance".equals(c.getSourceField()))
                .findFirst().orElseThrow();
        assertEquals("where_condition", balance.getRole());
        assertEquals("tab2", balance.getSourceTableShort());
        assertTrue(balance.getClause().replace(" ", "").toUpperCase().contains("BALANCE>0"));
    }

    @Test
    void testExtractConditionFields_SingleTableNoAliasFallback() throws Exception {
        String sql = "INSERT INTO tgt SELECT a FROM tab1 WHERE balance > 0";
        Map<String, String> aliasMap = buildAliasMapByAnalyzer(sql);
        Statement stmt = analyzer.parse(sql);
        var ins = (net.sf.jsqlparser.statement.insert.Insert) stmt;
        var select = (net.sf.jsqlparser.statement.select.PlainSelect) ins.getSelect().getSelectBody();

        List<ConditionField> cfs = extractor.extractConditionFields(select, aliasMap);

        assertEquals(1, cfs.size());
        assertEquals("where_condition", cfs.get(0).getRole());
        assertEquals("balance", cfs.get(0).getSourceField());
        // 无 alias 单表兜底：归属唯一源表
        assertEquals("tab1", cfs.get(0).getSourceTableShort());
    }

    @Test
    void testExtractConditionFields_MultiTableNoAlias_DdlUniqueMatch() throws Exception {
        // 多表无 alias：按 table_column_meta DDL 字段唯一匹配归属
        com.hb.extractor.mapper.TableColumnMetaMapper metaMapper =
                Mockito.mock(com.hb.extractor.mapper.TableColumnMetaMapper.class);
        when(metaMapper.selectList(any())).thenAnswer(inv -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?> w = inv.getArgument(0);
            // MyBatis-Plus 3.5.7: paramNameValuePairs 惰性填充，需先触发 SQL 段求值再取参值
            w.getSqlSegment();
            String tableName = String.valueOf(w.getParamNameValuePairs().values().iterator().next());
            if ("tab1".equals(tableName)) {
                com.hb.extractor.entity.TableColumnMeta m = new com.hb.extractor.entity.TableColumnMeta();
                m.setTableName("tab1"); m.setColumnName("K");
                return java.util.List.of(m);
            }
            if ("tab2".equals(tableName)) {
                com.hb.extractor.entity.TableColumnMeta m = new com.hb.extractor.entity.TableColumnMeta();
                m.setTableName("tab2"); m.setColumnName("BALANCE");
                return java.util.List.of(m);
            }
            return java.util.Collections.emptyList();
        });
        setField(extractor, "tableColumnMetaMapper", metaMapper);

        String sql = "INSERT INTO tgt SELECT a FROM tab1 JOIN tab2 ON k = k WHERE balance > 0";
        Map<String, String> aliasMap = buildAliasMapByAnalyzer(sql);
        Statement stmt = analyzer.parse(sql);
        var ins = (net.sf.jsqlparser.statement.insert.Insert) stmt;
        var select = (net.sf.jsqlparser.statement.select.PlainSelect) ins.getSelect().getSelectBody();

        List<ConditionField> cfs = extractor.extractConditionFields(select, aliasMap);

        // balance 仅 tab2 有 → 唯一匹配；k 仅 tab1 有 → 唯一匹配
        ConditionField balance = cfs.stream().filter(c -> "balance".equals(c.getSourceField())).findFirst().orElseThrow();
        assertEquals("tab2", balance.getSourceTableShort());
        ConditionField k = cfs.stream().filter(c -> "k".equals(c.getSourceField())).findFirst().orElseThrow();
        assertEquals("tab1", k.getSourceTableShort());
    }

    @Test
    void testExtractConditionFields_MultiMatchUnowned() throws Exception {
        // DDL 多重匹配：sourceTableShort=null（落库保留、同步跳过）
        com.hb.extractor.mapper.TableColumnMetaMapper metaMapper =
                Mockito.mock(com.hb.extractor.mapper.TableColumnMetaMapper.class);
        when(metaMapper.selectList(any())).thenAnswer(inv -> {
            com.hb.extractor.entity.TableColumnMeta m = new com.hb.extractor.entity.TableColumnMeta();
            m.setTableName("both"); m.setColumnName("BALANCE");
            return java.util.List.of(m);
        });
        setField(extractor, "tableColumnMetaMapper", metaMapper);

        String sql = "INSERT INTO tgt SELECT a FROM tab1 JOIN tab2 ON k = k WHERE balance > 0";
        Map<String, String> aliasMap = buildAliasMapByAnalyzer(sql);
        Statement stmt = analyzer.parse(sql);
        var ins = (net.sf.jsqlparser.statement.insert.Insert) stmt;
        var select = (net.sf.jsqlparser.statement.select.PlainSelect) ins.getSelect().getSelectBody();

        List<ConditionField> cfs = extractor.extractConditionFields(select, aliasMap);
        ConditionField balance = cfs.stream().filter(c -> "balance".equals(c.getSourceField())).findFirst().orElseThrow();
        assertNull(balance.getSourceTableShort(), "DDL 多重匹配应不归属");
    }

    // ===== 修复 A / C: 无别名表字段归属 + UNION 子查询字段穿透 =====

    /** 模拟 LineageExtractor 真实流程: 用 analyzer.extractAliasMap 构造 aliasMap */
    private Map<String, String> buildAliasMapByAnalyzer(String sql) throws Exception {
        Statement stmt = analyzer.parse(sql);
        assertNotNull(stmt, "SQL 应解析成功");
        var insert = (net.sf.jsqlparser.statement.insert.Insert) stmt;
        var plainSelect = (net.sf.jsqlparser.statement.select.PlainSelect) insert.getSelect().getSelectBody();
        return analyzer.extractAliasMap(plainSelect);
    }

    @Test
    void testNoAliasSingleTable_FieldAttribution() throws Exception {
        // dwa_c_cus_pri_cust_prft block 16 场景: FROM VT_C_CUS_PRI_CUST_PRFT_01 无别名
        String sql = "INSERT INTO {CDM}.C_CUS_PRI_CUST_PRFT " +
                "SELECT DATE '{ETL_TX_DT}', cust_no, txn_org_no, '999', SUM(cust_day_prft) " +
                "FROM {CDM}.VT_C_CUS_PRI_CUST_PRFT_01 " +
                "GROUP BY cust_no, txn_org_no";
        Map<String, String> aliasMap = buildAliasMapByAnalyzer(sql);
        assertEquals(1, aliasMap.size(), "无别名单表应有隐式 alias 登记");

        List<String> ddlFields = Arrays.asList("data_dt", "cust_no", "txn_org_no", "prft_type", "cust_day_prft");
        List<FieldMapping> result = extractor.extract(sql, "c_cus_pri_cust_prft", aliasMap, ddlFields);
        assertEquals(5, result.size());

        FieldMapping custNo = result.stream()
                .filter(fm -> "cust_no".equals(fm.getTargetField())).findFirst().orElse(null);
        assertNotNull(custNo, "应有 cust_no 映射");
        assertTrue(custNo.getInputs().stream()
                        .anyMatch(ti -> "cust_no".equals(ti.getSourceField())
                                     && "vt_c_cus_pri_cust_prft_01".equals(ti.getSourceTableShort())),
                "cust_no 输入应归属到 vt_c_cus_pri_cust_prft_01, 实际 inputs: "
                        + custNo.getInputs().stream().map(ti -> ti.getSourceField() + "->" + ti.getSourceTableShort()).toList());

        FieldMapping dayPrft = result.stream()
                .filter(fm -> "cust_day_prft".equals(fm.getTargetField())).findFirst().orElse(null);
        assertNotNull(dayPrft, "应有 cust_day_prft 映射");
        assertTrue(dayPrft.getInputs().stream()
                        .anyMatch(ti -> "cust_day_prft".equals(ti.getSourceField())
                                     && "vt_c_cus_pri_cust_prft_01".equals(ti.getSourceTableShort())),
                "SUM(cust_day_prft) 输入应归属到 vt_c_cus_pri_cust_prft_01");
    }

    @Test
    void testUnionSubqueryColumnPassthrough() throws Exception {
        // dwa_c_agt_ldr_acct_intst block 4 场景: FROM (SELECT...UNION ALL SELECT...) TAA GROUP BY TAA.acct_no
        String sql = "INSERT INTO {CDM}.C_AGT_LDR_ACCT_INTST " +
                "SELECT DATE '{ETL_TX_DT}' " +
                "      ,TAA.acct_no " +
                "      ,SUM(day_intst)   AS day_intst " +
                "      ,SUM(month_intst) AS month_intst " +
                "      ,SUM(quart_intst) AS quart_intst " +
                "      ,SUM(year_intst)  AS year_intst " +
                "FROM (SELECT TLX.acct_no " +
                "            ,day_intst AS day_intst " +
                "            ,day_intst AS month_intst " +
                "            ,day_intst AS quart_intst " +
                "            ,day_intst AS year_intst " +
                "       FROM {CDM}.VT_C_AGT_LDR_ACCT_INTST_01 TLX " +
                "      UNION ALL " +
                "      SELECT TLD.acct_no " +
                "            ,0 AS day_intst " +
                "            ,TLD.month_intst AS month_intst " +
                "            ,TLD.quart_intst AS quart_intst " +
                "            ,TLD.year_intst  AS year_intst " +
                "       FROM {CDM}.C_AGT_LDR_ACCT_INTST TLD " +
                "      WHERE data_dt = DATE '{I_LAST_DT}' " +
                "      ) TAA " +
                " GROUP BY TAA.acct_no";
        Map<String, String> aliasMap = buildAliasMapByAnalyzer(sql);
        // TAA → null 占位（子查询 alias 不映射具体表）
        assertTrue(aliasMap.containsKey("TAA"), "aliasMap 应含子查询 alias 占位 TAA");
        assertNull(aliasMap.get("TAA"));

        List<String> ddlFields = Arrays.asList("data_dt", "acct_no", "day_intst",
                "month_intst", "quart_intst", "year_intst");
        List<FieldMapping> result = extractor.extract(sql, "c_agt_ldr_acct_intst", aliasMap, ddlFields);
        assertEquals(6, result.size());

        // acct_no: 沿 UNION 两分支穿透 → vt_01.acct_no + c_agt_ldr_acct_intst.acct_no
        FieldMapping acctNo = result.stream()
                .filter(fm -> "acct_no".equals(fm.getTargetField())).findFirst().orElse(null);
        assertNotNull(acctNo);
        String acctNoInputs = acctNo.getInputs().stream()
                .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList().toString();
        assertTrue(acctNo.getInputs().stream().anyMatch(ti ->
                        "vt_c_agt_ldr_acct_intst_01".equals(ti.getSourceTableShort())
                     && "acct_no".equals(ti.getSourceField())),
                "acct_no 应穿透到 vt_c_agt_ldr_acct_intst_01.acct_no, 实际: " + acctNoInputs);
        assertTrue(acctNo.getInputs().stream().anyMatch(ti ->
                        "c_agt_ldr_acct_intst".equals(ti.getSourceTableShort())
                     && "acct_no".equals(ti.getSourceField())),
                "acct_no 应穿透到 c_agt_ldr_acct_intst.acct_no, 实际: " + acctNoInputs);

        // day_intst: 分支 1 为 day_intst 裸列, 分支 2 为字面量 0 → 仅 vt_01.day_intst
        FieldMapping dayIntst = result.stream()
                .filter(fm -> "day_intst".equals(fm.getTargetField())).findFirst().orElse(null);
        assertNotNull(dayIntst);
        assertTrue(dayIntst.getInputs().stream().anyMatch(ti ->
                        "vt_c_agt_ldr_acct_intst_01".equals(ti.getSourceTableShort())
                     && "day_intst".equals(ti.getSourceField())),
                "day_intst 应穿透到 vt_c_agt_ldr_acct_intst_01.day_intst, 实际: "
                        + dayIntst.getInputs().stream().map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList());

        // data_dt: DATE 字面量, 无字段归属（inputs 为空或不产生带表归属的输入）
        FieldMapping dataDt = result.stream()
                .filter(fm -> "data_dt".equals(fm.getTargetField())).findFirst().orElse(null);
        assertNotNull(dataDt);
        assertTrue(dataDt.getInputs().stream().noneMatch(ti -> ti.getSourceTableShort() != null),
                "data_dt 为字面量, 不应有表归属输入");
    }
}
