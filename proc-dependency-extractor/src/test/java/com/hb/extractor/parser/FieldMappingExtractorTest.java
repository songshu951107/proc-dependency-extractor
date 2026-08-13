package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.FieldMapping;
import com.hb.extractor.parser.dto.TransformInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FieldMappingExtractorTest {

    private FieldMappingExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new FieldMappingExtractor();
    }

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
        assertEquals("NVL", extractor.classifyLogicType("COALESCE(T01.a, T02.b, T03.c)"));
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
        // 混合运算取首个运算符
        assertEquals("ADDITION", extractor.classifyLogicType("T01.amt + T02.fee - T03.tax"));
        assertEquals("SUBTRACTION", extractor.classifyLogicType("T01.amt - T02.fee + T03.tax"));
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
}
