package com.hb.extractor.parser;

import com.hb.extractor.ProcExtractorApplication;
import com.hb.extractor.parser.dto.ConditionField;
import com.hb.extractor.parser.dto.FieldMapping;
import com.hb.extractor.parser.dto.LineageEdge;
import com.hb.extractor.parser.dto.LineageParseResult;
import com.hb.extractor.parser.dto.TransformInput;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ProcExtractorApplication.class)
class LineageExtractorTest {

    @Autowired
    private LineageExtractor lineageExtractor;

    private Map<String, String> schemaVarMap;

    @BeforeEach
    void setUp() {
        schemaVarMap = new HashMap<>();
        schemaVarMap.put("CDM", "etl.dwa_db_cdm");
        schemaVarMap.put("FDM", "etl.dwa_db_fdm");
    }

    @Test
    void testCreateTempTable() {
        String sql = "CREATE TEMPORARY TABLE {CDM}.VT_X AS SELECT a.id FROM {FDM}.F_A a JOIN {FDM}.F_B b ON a.id = b.aid";
        List<String> blocks = Arrays.asList(sql);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        assertEquals(2, result.getEdges().size());
        LineageEdge e1 = result.getEdges().get(0);
        // JsqlSqlAnalyzer.tableRefInfo 统一 toLowerCase 落库
        assertEquals("f_a", e1.getSourceTableShort());
        assertEquals("vt_x", e1.getTargetTableShort());
        assertTrue(e1.isTargetIsTemp());
        assertEquals("CREATE TEMP", e1.getOperation());
        assertEquals(1, e1.getSqlBlockSeq());
    }

    /**
     * 场景：CREATE TEMPORARY TABLE (列定义，无 AS SELECT) + 后续 INSERT INTO ... SELECT。
     * 表名不匹配 VT_/TMP_/_TMP 后缀规则（如 a_pfal_deposit_term_inf_v），靠 CREATE TEMP 块的列定义识别为临时表。
     * 修复前：INSERT 块 targetIsTemp=isTempTableName(name)=false → edge 落库 target_is_temp=0 → Neo4j is_temp=False（误判为物理表）。
     * 修复后：INSERT 块检查目标表是否在同 proc CREATE TEMP 定义过（tempColumnCache 含）→ targetIsTemp=true。
     */
    @Test
    void testInsertIntoTempTableDefinedByCreateTemp_MarksTargetIsTempTrue() {
        schemaVarMap.put("PIDRPT", "pidrpt");
        String createTemp = "CREATE TEMPORARY TABLE {PIDRPT}.a_pfal_deposit_term_inf_v (\n" +
                "cust_no varchar(64),\n" +
                "acct_bal decimal(18,2)\n" +
                ")";
        String insertSelect = "INSERT INTO {PIDRPT}.a_pfal_deposit_term_inf_v " +
                "SELECT cust_no, acct_bal FROM {FDM}.f_ac_ctds_ldr_acct T";

        List<String> blocks = Arrays.asList(createTemp, insertSelect);
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "PIDRPT.TARGET");

        // CREATE TEMP (cols) 无 SELECT → 无 edge；INSERT SELECT 有 source → 有 edge
        assertFalse(result.getEdges().isEmpty(), "INSERT 块应产生 edge");
        LineageEdge insEdge = result.getEdges().get(0);
        assertEquals("INSERT SELECT", insEdge.getOperation());
        assertTrue(insEdge.isTargetIsTemp(),
                "INSERT 临时表（同 proc CREATE TEMP 定义）的 edge.targetIsTemp 应为 true, 实际=" + insEdge.isTargetIsTemp());
    }

    /**
     * 场景：INSERT SELECT 外层 FROM (带 JOIN 的子查询) T，外层引用 T.col。
     * 复现 DWA_PID_A_D_LN_CREDIT_CARD_ACCT_INF.py 断裂：
     * 外层 SELECT T.begin_year_np_ind FROM (SELECT CASE WHEN t4.fiveclass_class_cd ... AS BEGIN_YEAR_NP_IND
     *   FROM {FDM}.f_ac_ccrd_cny_credit_acct T1 LEFT JOIN {CDM}.c_agt_credit_card_acct_stand t4) T
     * 期望：穿透后 begin_year_np_ind 的 inputs 含 c_agt_credit_card_acct_stand.fiveclass_class_cd。
     * 修复前：source_table_short=NULL（130/137 条映射无表归属）→ Neo4j 无 INPUT_TO 边 → 影响分析断链。
     */
    @Test
    void testSubqueryWithJoin_ColumnPassthroughResolvesJoinTable() {
        String insertSelect = "INSERT INTO {PID}.a_d_ln_credit_card_acct_inf (begin_year_np_ind, fiveclass_class_cd) " +
                "SELECT T.begin_year_np_ind, T.fiveclass_class_cd " +
                "FROM ( " +
                "  SELECT CASE WHEN t4.fiveclass_class_cd IN ('03','04','05') THEN 'Y' ELSE 'N' END AS BEGIN_YEAR_NP_IND, " +
                "         T1.fivclaid AS FIVECLASS_CLASS_CD " +
                "  FROM {FDM}.f_ac_ccrd_cny_credit_acct T1 " +
                "  LEFT JOIN {CDM}.c_agt_credit_card_acct_stand t4 ON T1.acct_no = t4.acct_no " +
                ") T";

        List<String> blocks = Arrays.asList(insertSelect);
        schemaVarMap.put("PID", "pid");
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "PID.TARGET");

        List<FieldMapping> mappings = result.getFieldMappings();
        assertFalse(mappings.isEmpty(), "应提取到字段映射");

        FieldMapping beginNp = mappings.stream()
                .filter(fm -> "begin_year_np_ind".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(beginNp, "应存在 begin_year_np_ind 映射, 实际: "
                + mappings.stream().map(FieldMapping::getTargetField).toList());

        boolean hasStandInput = beginNp.getInputs() != null && beginNp.getInputs().stream()
                .anyMatch(ti -> "c_agt_credit_card_acct_stand".equals(ti.getSourceTableShort())
                        && "fiveclass_class_cd".equals(ti.getSourceField()));
        assertTrue(hasStandInput, "穿透后 begin_year_np_ind 的 inputs 应含 c_agt_credit_card_acct_stand.fiveclass_class_cd, 实际 inputs="
                + (beginNp.getInputs() == null ? "null" : beginNp.getInputs().stream()
                        .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));
    }

    /**
     * 场景：两层嵌套子查询穿透。
     * 外层 SELECT T.np_ind FROM ( 子查询A ) T；
     * 子查询A: SELECT CASE WHEN t.fivclaid IN ('33','34','35') ... AS NP_IND FROM ( 子查询B ) t；
     * 子查询B: SELECT CASE WHEN s.fiveclass_class_cd = '01' THEN '31' ... AS FIVCLAID FROM cdm.stand s。
     * 复现 DWA_PID_A_D_LN_CREDIT_CARD_ACCT_INF.py L377/L409：
     * NP_IND ← t.FIVCLAID（子查询B 别名）← t11.fiveclass_class_cd（stand 表）。
     * 修复前：子查询A 里 t 映射 null，穿透一层即断 → NP_IND 无 stand 输入。
     */
    @Test
    void testNestedSubquery_TwoLevelColumnPassthrough() {
        String insertSelect = "INSERT INTO {PID}.a_d_ln_credit_card_acct_inf (np_ind) " +
                "SELECT T.np_ind " +
                "FROM ( " +
                "  SELECT CASE WHEN t.fivclaid IN ('33','34','35') THEN 'Y' ELSE 'N' END AS NP_IND " +
                "  FROM ( " +
                "    SELECT CASE WHEN s.fiveclass_class_cd = '01' THEN '31' " +
                "                WHEN s.fiveclass_class_cd = '02' THEN '32' " +
                "                ELSE s.fiveclass_class_cd END AS FIVCLAID " +
                "    FROM {CDM}.c_agt_credit_card_acct_stand s " +
                "  ) t " +
                ") T";

        List<String> blocks = Arrays.asList(insertSelect);
        schemaVarMap.put("PID", "pid");
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "PID.TARGET");

        List<FieldMapping> mappings = result.getFieldMappings();
        assertFalse(mappings.isEmpty(), "应提取到字段映射");

        FieldMapping npInd = mappings.stream()
                .filter(fm -> "np_ind".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(npInd, "应存在 np_ind 映射, 实际: "
                + mappings.stream().map(FieldMapping::getTargetField).toList());

        boolean hasStandInput = npInd.getInputs() != null && npInd.getInputs().stream()
                .anyMatch(ti -> "c_agt_credit_card_acct_stand".equals(ti.getSourceTableShort())
                        && "fiveclass_class_cd".equals(ti.getSourceField()));
        assertTrue(hasStandInput, "两层穿透后 np_ind 的 inputs 应含 c_agt_credit_card_acct_stand.fiveclass_class_cd, 实际 inputs="
                + (npInd.getInputs() == null ? "null" : npInd.getInputs().stream()
                        .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));
    }

    /**
     * 场景：主 FROM 有别名子查询的裸列两层穿透（复现 dwa_C_CUS_EMP_NO_MAPP_INF 块8 真实结构）。
     * 外层: SELECT NVL(org_no,'') FROM ( 子查询A ) B WHERE RN=1   ← 子查询有别名 B，但外层用裸列；
     * 子查询A: SELECT org_no ... FROM ( 子查询B ) A WHERE RANK='1' ← 子查询有别名 A，中层也用裸列；
     * 子查询B: SELECT T01.org_no FROM 物理表 T01（VT 临时表，UNION 结构）。
     * SQL 允许不带子查询别名前缀裸引用主 FROM 子查询的列。
     * 修复前：nestedIndex 只给无别名子查询建 "" 裸列入口，有别名的没建 → 中层裸列断链。
     */
    @Test
    void testNestedSubquery_AliasedMainFromBareColumnReference() {
        String insertSelect = "INSERT INTO {CDM}.c_cus_emp_no_mapp_inf (org_no) " +
                "SELECT NVL(org_no, '') AS org_no " +
                "FROM ( " +
                "  SELECT org_no, ROW_NUMBER() OVER(PARTITION BY wage_no ORDER BY rk) AS RN " +
                "  FROM ( " +
                "    SELECT T01.org_no, T01.wage_no, '2' AS rk FROM {CDM}.vt_c_cus_emp_no_mapp_inf_01 T01 " +
                "  ) A " +
                "  WHERE rank_col = '1' " +
                ") B " +
                "WHERE rn = 1";

        List<String> blocks = Arrays.asList(insertSelect);
        schemaVarMap.put("CDM", "cdm");
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        List<FieldMapping> mappings = result.getFieldMappings();
        assertFalse(mappings.isEmpty(), "应提取到字段映射");

        FieldMapping orgNo = mappings.stream()
                .filter(fm -> "org_no".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(orgNo, "应存在 org_no 映射, 实际: "
                + mappings.stream().map(FieldMapping::getTargetField).toList());

        boolean hasVtInput = orgNo.getInputs() != null && orgNo.getInputs().stream()
                .anyMatch(ti -> "vt_c_cus_emp_no_mapp_inf_01".equals(ti.getSourceTableShort())
                        && "org_no".equals(ti.getSourceField()));
        assertTrue(hasVtInput, "有别名子查询裸列穿透后 org_no 的 inputs 应含 vt_c_cus_emp_no_mapp_inf_01.org_no, 实际 inputs="
                + (orgNo.getInputs() == null ? "null" : orgNo.getInputs().stream()
                        .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));
    }

    /**
     * 场景：JOIN 右侧子查询 SELECT T.* + 窗口函数，外层按别名引用其列。
     * 复现 dwa_pid_a_d_ln_credit_card_dtl_tab 断链（24 个 Transform 有输出无输入）：
     * INNER JOIN ( SELECT T.*, ROW_NUMBER() OVER(...) AS RN FROM {PID}.a_d_ln_credit_card_inf T
     *              WHERE ... ) T3 ON T1.ACCT_NO=T3.ACCT_NO
     * 外层: T3.PROM_ORG_NO → 子查询 SELECT T.* 是 AllColumns 无法按列名索引 → 断链。
     * 修复：SELECT * 的子查询 FROM 单表时, 记通配入口, 外层 别名.列 解析为 单表.同名列。
     */
    @Test
    void testJoinSubquery_SelectStarWildcardPassthrough() {
        String insertSelect = "INSERT INTO {CDM}.c_ln_card_dtl (prom_org_no, acct_no) " +
                "SELECT T3.PROM_ORG_NO, T1.ACCT_NO " +
                "FROM {PID}.a_d_ln_credit_card_acct_relax_tab T1 " +
                "INNER JOIN ( SELECT T.*, ROW_NUMBER() OVER(PARTITION BY ACCT_NO ORDER BY ISSUED_DT DESC) AS RN " +
                "             FROM {PID}.a_d_ln_credit_card_inf T WHERE T.DATA_DT = DATE '{ETL_TX_DT}' ) T3 " +
                "ON T1.ACCT_NO = T3.ACCT_NO AND T3.RN = 1";

        List<String> blocks = Arrays.asList(insertSelect);
        schemaVarMap.put("CDM", "cdm");
        schemaVarMap.put("PID", "pid");
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        List<FieldMapping> mappings = result.getFieldMappings();
        assertFalse(mappings.isEmpty(), "应提取到字段映射");

        FieldMapping promOrgNo = mappings.stream()
                .filter(fm -> "prom_org_no".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(promOrgNo, "应存在 prom_org_no 映射, 实际: "
                + mappings.stream().map(FieldMapping::getTargetField).toList());

        boolean hasInfInput = promOrgNo.getInputs() != null && promOrgNo.getInputs().stream()
                .anyMatch(ti -> "a_d_ln_credit_card_inf".equals(ti.getSourceTableShort())
                        && "prom_org_no".equals(ti.getSourceField()));
        assertTrue(hasInfInput, "SELECT * 通配穿透后 prom_org_no 的 inputs 应含 a_d_ln_credit_card_inf.prom_org_no, 实际 inputs="
                + (promOrgNo.getInputs() == null ? "null" : promOrgNo.getInputs().stream()
                        .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));
    }

    /**
     * 场景：多表 JOIN + 裸列引用（无别名前缀）。
     * 复现 dwa_pid_a_d_ag_agent_pay_cus_inf 断链：BAD_LN_BAL 只存在于临时表（CREATE TEMP 列定义），
     * 消费块 4 表 JOIN 裸引用 BAD_LN_BAL 无 t1. 前缀 → 多表无法归属 → 断链终止于临时表。
     * 修复：多表 JOIN 时裸列按列名在候选表中唯一匹配（临时表用 CREATE TEMP 列定义、物理表用 DDL 元数据），
     * 仅唯一命中时归属（多表都有该列则保守置空）。
     */
    @Test
    void testMultiTableJoinBareColumnUniqueMatch() {
        schemaVarMap.put("PID", "etl.dwa_db_pid");
        String createTemp = "CREATE TEMPORARY TABLE {PID}.A_D_AGENT_PAY_CUS_INF_ (\n" +
                "CUST_NO varchar(64),\n" +
                "BAD_LN_BAL decimal(18,2)\n" +
                ")";
        // 物理表用无 DDL 元数据的虚构名: 唯一匹配只可能来自临时表列定义, 测试稳定
        String insertSelect = "INSERT INTO {CDM}.c_agent_pay_cus_inf (cust_no, bad_ln_bal) " +
                "SELECT t.CUST_NO, BAD_LN_BAL " +
                "FROM {CDM}.c_test_nometab_a t " +
                "LEFT JOIN {PID}.A_D_AGENT_PAY_CUS_INF_ t1 ON t.CUST_NO = t1.CUST_NO " +
                "LEFT JOIN {FDM}.f_test_nometab_b t2 ON t.CUST_NO = t2.CUST_NO";

        List<String> blocks = Arrays.asList(createTemp, insertSelect);
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        // CREATE TEMP (列定义) 块无 SELECT 不产生 mappings, 消费块 edgeIndex 从 0 起, 直接全量过滤
        List<FieldMapping> mappings = result.getFieldMappings();
        FieldMapping badLnBal = mappings.stream()
                .filter(fm -> "bad_ln_bal".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(badLnBal, "消费块应存在 bad_ln_bal 映射");

        boolean hasTempInput = badLnBal.getInputs() != null && badLnBal.getInputs().stream()
                .anyMatch(ti -> "a_d_agent_pay_cus_inf_".equals(ti.getSourceTableShort())
                        && "bad_ln_bal".equals(ti.getSourceField()));
        assertTrue(hasTempInput, "多表 JOIN 裸列唯一匹配后 bad_ln_bal 应归属临时表 a_d_agent_pay_cus_inf_.bad_ln_bal, 实际 inputs="
                + (badLnBal.getInputs() == null ? "null" : badLnBal.getInputs().stream()
                        .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));
    }

    /**
     * 场景：CTAS 形式临时表（CREATE ... AS SELECT, 无内联列定义）+ 后续多表 JOIN 裸列引用。
     * 复现 dwa_pid_a_d_ag_agent_pay_cus_inf 真实断链：临时表列由 SELECT 派生（AS 别名），
     * 内联列定义缓存不含 CTAS 派生列 → 裸列唯一匹配候选缺失 → BAD_LN_BAL 无法归属。
     * 修复：CTAS 块解析后把派生列（targetField）也写入 tempColumnCache。
     */
    @Test
    void testCtasTempTableDerivedColumnsFeedBareColumnMatch() {
        schemaVarMap.put("PID", "etl.dwa_db_pid");
        String ctas = "CREATE TEMPORARY TABLE {PID}.VT_AGENT_PAY_ AS " +
                "SELECT CUST_NO, SUM(NVL(BAD_LN_BAL,0)) AS BAD_LN_BAL " +
                "FROM {FDM}.f_test_nometab_c T GROUP BY CUST_NO";
        String insertSelect = "INSERT INTO {CDM}.c_agent_pay_cus (cust_no, bad_ln_bal) " +
                "SELECT t.CUST_NO, BAD_LN_BAL " +
                "FROM {CDM}.c_test_nometab_a t " +
                "LEFT JOIN {PID}.VT_AGENT_PAY_ t1 ON t.CUST_NO = t1.CUST_NO";

        List<String> blocks = Arrays.asList(ctas, insertSelect);
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        FieldMapping badLnBal = result.getFieldMappings().stream()
                .filter(fm -> "bad_ln_bal".equals(fm.getTargetField()))
                .filter(fm -> fm.getEdgeIndex() >= 1)  // 只看消费块（CTAS 块有 1 条边）
                .findFirst().orElse(null);
        assertNotNull(badLnBal, "消费块应存在 bad_ln_bal 映射");

        boolean hasTempInput = badLnBal.getInputs() != null && badLnBal.getInputs().stream()
                .anyMatch(ti -> "vt_agent_pay_".equals(ti.getSourceTableShort())
                        && "bad_ln_bal".equals(ti.getSourceField()));
        assertTrue(hasTempInput, "CTAS 派生列入缓存后 bad_ln_bal 应归属临时表 vt_agent_pay_.bad_ln_bal, 实际 inputs="
                + (badLnBal.getInputs() == null ? "null" : badLnBal.getInputs().stream()
                        .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));
    }

    /**
     * 形态1（T14）：3 层嵌套 + group_concat + 内层 a.*（多表 JOIN）。
     * 复现 dwa_pid_a_d_ln_credit_card_dtl_tab 断链：
     * 外层 T14.ORG_NAME → T14 层 group_concat(org_name) → 内层 select b.xaccount, a.* from a left join b。
     * 断链根因：内层 2 表时 AllTableColumns(a.*) 被跳过 → org_name 无 __star__ 入口 → 无法归属。
     */
    @Test
    void testThreeLevelNestedGroupConcat_StarPrefixMultiTable() {
        schemaVarMap.put("PID", "etl.dwa_db_pid");
        String insertSelect = "INSERT INTO {CDM}.c_credit_card_dtl (acct_no, org_name) " +
                "SELECT T1.ACCT_NO, T14.ORG_NAME " +
                "FROM {CDM}.c_test_nometab_a T1 " +
                "LEFT JOIN (select xaccount, group_concat(distinct org_name) org_name " +
                "           from (select b.xaccount, a.* " +
                "                 from {FDM}.f_test_nometab_b a " +
                "                 left join {FDM}.f_test_nometab_c b on a.card_no = b.card_nbr) t14 " +
                "           group by xaccount) T14 " +
                "  ON T1.ACCT_NO = T14.XACCOUNT";

        List<String> blocks = Collections.singletonList(insertSelect);
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        FieldMapping orgName = result.getFieldMappings().stream()
                .filter(fm -> "org_name".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(orgName, "应存在 org_name 映射");

        boolean resolved = orgName.getInputs() != null && orgName.getInputs().stream()
                .anyMatch(ti -> "f_test_nometab_b".equals(ti.getSourceTableShort())
                        && "org_name".equals(ti.getSourceField()));
        assertTrue(resolved, "T14.ORG_NAME 应穿透 a.* 归属 f_test_nometab_b.org_name, 实际 inputs="
                + (orgName.getInputs() == null ? "null" : orgName.getInputs().stream()
                        .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));
    }

    /**
     * 形态2（coll）：4 层嵌套 + 中间层裸 SELECT * + 内层 CASE 派生列。
     * 复现 dwa_pid_a_d_ln_credit_card_dtl_tab 断链：
     * 外层 coll.THE_LAST_3... → max(case when time_range=3 then coll_result) →
     * c 层 select * → b 层 select *, row_number() → a 层（物理表 + case 派生 time_range）。
     * 断链根因：①深度限死 ≤2；②中间层裸 * 不透传内层已索引列。
     */
    @Test
    void testFourLevelNestedBareSelectStar_Passthrough() {
        schemaVarMap.put("PID", "etl.dwa_db_pid");
        String insertSelect = "INSERT INTO {CDM}.c_credit_card_dtl (custcert_no, the_last_3_month_call_result) " +
                "SELECT T1.CUSTCERT_NO, coll.THE_LAST_3_MONTH_CALL_RESULT " +
                "FROM {CDM}.c_test_nometab_a T1 " +
                "LEFT JOIN (select custcert_no, max(case when time_range=3 then coll_result end) the_last_3_month_call_result " +
                "           from (select * from " +
                "                 (select *, row_number() over(partition by custcert_no, time_range order by coll_time desc) T " +
                "                  from (select custcert_no, coll_result, coll_time, " +
                "                        case when coll_time >= '2026-01-01' then 3 else 6 end as time_range " +
                "                        from {FDM}.f_test_nometab_d) a) b " +
                "                 where b.T = 1) c " +
                "           group by custcert_no) coll " +
                "  ON T1.CUSTCERT_NO = coll.CUSTCERT_NO";

        List<String> blocks = Collections.singletonList(insertSelect);
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        FieldMapping callResult = result.getFieldMappings().stream()
                .filter(fm -> "the_last_3_month_call_result".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(callResult, "应存在 the_last_3_month_call_result 映射");

        boolean hasCollResult = callResult.getInputs() != null && callResult.getInputs().stream()
                .anyMatch(ti -> "f_test_nometab_d".equals(ti.getSourceTableShort())
                        && "coll_result".equals(ti.getSourceField()));
        assertTrue(hasCollResult, "coll.THE_LAST_3_MONTH_CALL_RESULT 应穿透 4 层归属 f_test_nometab_d.coll_result, 实际 inputs="
                + (callResult.getInputs() == null ? "null" : callResult.getInputs().stream()
                        .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));
    }

    @Test
    void testInsertSelect() {
        String sql = "INSERT INTO {CDM}.TARGET SELECT a.id FROM {FDM}.F_A a JOIN {CDM}.VT_X x ON a.id = x.id";
        List<String> blocks = Arrays.asList(sql);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        assertEquals(2, result.getEdges().size());
        LineageEdge e0 = result.getEdges().get(0);
        assertEquals("f_a", e0.getSourceTableShort());
        assertEquals("target", e0.getTargetTableShort());
        assertFalse(e0.isTargetIsTemp());
        assertEquals("INSERT SELECT", e0.getOperation());
    }

    @Test
    void testSubqueryExtraction() {
        String sql = "INSERT INTO {CDM}.TARGET SELECT a.id FROM {FDM}.F_A a " +
                "LEFT JOIN (SELECT b.id FROM {FDM}.F_B b WHERE b.sts='1') sq ON a.id = sq.id";
        List<String> blocks = Arrays.asList(sql);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        assertEquals(2, result.getEdges().size());
        boolean hasFB = result.getEdges().stream()
                .anyMatch(e -> "f_b".equals(e.getSourceTableShort()));
        assertTrue(hasFB, "子查询内的 F_B 应被提取为 source");
    }

    @Test
    void testJoinClauseExtraction() {
        String sql = "INSERT INTO {CDM}.TARGET SELECT a.id FROM {FDM}.F_A a " +
                "JOIN {FDM}.F_B b ON a.cust_id = b.id";
        List<String> blocks = Arrays.asList(sql);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        assertFalse(result.getFilters().isEmpty());
        boolean hasJoin = result.getFilters().stream()
                .anyMatch(f -> "JOIN".equals(f.getFilterType()));
        assertTrue(hasJoin, "应提取到 JOIN 条件");
    }

    @Test
    void testWhereClauseExtraction() {
        String sql = "INSERT INTO {CDM}.TARGET SELECT a.id FROM {FDM}.F_A a WHERE a.sts = '1' AND a.dt >= '2024-01-01'";
        List<String> blocks = Arrays.asList(sql);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        boolean hasWhere = result.getFilters().stream()
                .anyMatch(f -> "WHERE".equals(f.getFilterType()));
        assertTrue(hasWhere, "应提取到 WHERE 条件");
    }

    @Test
    void testUpdateTargetExcluded() {
        String sql = "INSERT INTO {CDM}.TARGET SELECT a.id FROM {CDM}.TARGET a JOIN {FDM}.F_B b ON a.id = b.id";
        List<String> blocks = Arrays.asList(sql);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        boolean hasSelfLoop = result.getEdges().stream()
                .anyMatch(e -> "TARGET".equals(e.getSourceTableShort()) && "TARGET".equals(e.getTargetTableShort()));
        assertFalse(hasSelfLoop, "更新目标表不应产生自环");
    }

    @Test
    void testUnrecognizableBlock() {
        List<String> blocks = Arrays.asList("-- this is a comment", "x = 1");
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");
        assertTrue(result.getEdges().isEmpty());
    }

    @Test
    void testMalformedSql() {
        String sql = "INSERT INTO {CDM}.TARGET SELECT a.id FROM {FDM}.F_A a JOIN (SELECT * FROM {FDM}.F_B b";
        List<String> blocks = Arrays.asList(sql);
        assertDoesNotThrow(() -> lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET"));
    }

    @Test
    void testUnionAllSubquerySourceTables() {
        // dwa_c_agt_ldr_acct_intst block 4 场景: FROM (SELECT...UNION ALL SELECT...) TAA
        String sql = "INSERT INTO {CDM}.C_AGT_LDR_ACCT_INTST " +
                "SELECT DATE '{ETL_TX_DT}', TAA.acct_no, SUM(day_intst) AS day_intst " +
                "FROM (SELECT TLX.acct_no, day_intst AS day_intst " +
                "      FROM {CDM}.VT_C_AGT_LDR_ACCT_INTST_01 TLX " +
                "      UNION ALL " +
                "      SELECT TLD.acct_no, TLD.month_intst AS day_intst " +
                "      FROM {CDM}.C_AGT_LDR_ACCT_INTST TLD WHERE data_dt = DATE '{I_LAST_DT}') TAA " +
                "GROUP BY TAA.acct_no";
        List<String> blocks = Arrays.asList(sql);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.C_AGT_LDR_ACCT_INTST");

        // 目标表自读（C_AGT_LDR_ACCT_INTST 同时是源和目标）被过滤是预期；vt_01 → c_agt 边应存在
        assertTrue(result.getEdges().stream().anyMatch(e ->
                        "vt_c_agt_ldr_acct_intst_01".equals(e.getSourceTableShort())
                     && "c_agt_ldr_acct_intst".equals(e.getTargetTableShort())),
                "应有 vt_c_agt_ldr_acct_intst_01 -> c_agt_ldr_acct_intst 边, 实际: "
                        + result.getEdges().stream()
                                .map(e -> e.getSourceTableShort() + "->" + e.getTargetTableShort()).toList());
    }

    @Test
    void testRealScript() throws Exception {
        java.nio.file.Path scriptPath = java.nio.file.Paths.get("../jiaoben/dwa_c_agt_deposit_acct_inf.py");
        if (!java.nio.file.Files.exists(scriptPath)) {
            scriptPath = java.nio.file.Paths.get("jiaoben/dwa_c_agt_deposit_acct_inf.py");
        }
        if (!java.nio.file.Files.exists(scriptPath)) {
            scriptPath = java.nio.file.Paths.get("../../jiaoben/dwa_c_agt_deposit_acct_inf.py");
        }
        if (!java.nio.file.Files.exists(scriptPath)) {
            System.out.println("跳过 testRealScript: 脚本文件不存在");
            return;
        }
        String content = java.nio.file.Files.readString(scriptPath, java.nio.charset.StandardCharsets.UTF_8);

        com.hb.extractor.parser.SqlBlockExtractor extractor = new com.hb.extractor.parser.SqlBlockExtractor();
        List<String> blocks = extractor.extractBlocks(content);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.C_AGT_DEPOSIT_ACCT_INF");

        System.out.println("提取到 " + result.getEdges().size() + " 条派生关系");
        System.out.println("提取到 " + result.getFilters().size() + " 条 JOIN/WHERE 条件");
        for (LineageEdge e : result.getEdges()) {
            System.out.println("  " + e.getSourceTableShort() + " -> " + e.getTargetTableShort() + " (" + e.getOperation() + ")");
        }

        assertTrue(result.getEdges().size() >= 7,
                "应提取至少 7 条派生关系, 实际: " + result.getEdges().size());
        boolean hasFrozenBook = result.getEdges().stream()
                .anyMatch(e -> e.getSourceTableShort().contains("frozen_book"));
        assertTrue(hasFrozenBook, "应包含子查询内的 F_EV_CRBS_DEPOSIT_ACCT_FROZEN_BOOK");
    }

    @Test
    void testInsertIntoTempUsesCreateTempColumnDefinitions() {
        // proc 场景：先 CREATE TEMP 定义列，再 INSERT SELECT 裸列（无 INSERT 列列表、无 AS 别名）
        // 断裂根因：INSERT 块拿不到 CREATE TEMP 列定义 → targetField 退化为 expr_N
        schemaVarMap.put("PID", "etl.dwa_db_pid");
        String createTemp = "CREATE TEMPORARY TABLE {PID}.VT_TEST_02 (\n" +
                "CUST_NO varchar(64),\n" +
                "ORG_ID varchar(8),\n" +
                "DPST_BAL decimal(18,2)\n" +
                ")";
        String insertSelect = "INSERT INTO {PID}.VT_TEST_02 " +
                "SELECT CUST_NO, OPEN_ACCT_ORG_NO, ACCT_BAL " +
                "FROM {CDM}.C_AGT_DEPOSIT_ACCT_INF T";

        List<String> blocks = Arrays.asList(createTemp, insertSelect);
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "PID.TARGET");

        List<FieldMapping> mappings = result.getFieldMappings();
        assertFalse(mappings.isEmpty(), "应提取到字段映射");
        // INSERT 临时表的 targetField 应为 CREATE TEMP 定义的真实列名（按位置匹配），而非 expr_N
        boolean hasCustNo = mappings.stream()
                .anyMatch(fm -> "cust_no".equals(fm.getTargetField()));
        assertTrue(hasCustNo, "targetField 应为真实列名 cust_no, 而非 expr_1, 实际: "
                + mappings.stream().map(FieldMapping::getTargetField).toList());
        boolean hasExprPlaceholder = mappings.stream()
                .anyMatch(fm -> fm.getTargetField() != null && fm.getTargetField().startsWith("expr_"));
        assertFalse(hasExprPlaceholder, "不应出现 expr_N 占位列名, 实际: "
                + mappings.stream().map(FieldMapping::getTargetField).toList());
    }

    @Test
    void testCreateTempWithGbaseDistributedByAndTableComment() {
        // GBase CREATE TEMPORARY TABLE 语法：列定义后紧跟 )DISTRIBUTED BY ('COL') 和表级 comment '...'
        // JsqlParser 不认识 DISTRIBUTED BY / 表级 comment，会导致整个 CREATE TEMP 块解析失败，
        // tempColumnCache 没写入 → 后续 INSERT 块 target 退化为 expr_N。
        // 期望：preprocess 清理 GBase 语法后，CREATE TEMP 块能解析出列定义并缓存，
        //      INSERT SELECT 裸列块按位置匹配到真实列名（org_no / compy_cust_num）。
        schemaVarMap.put("PID", "etl.dwa_db_pid");
        String createTemp = "CREATE TEMPORARY TABLE {PID}.VT_GBASE_TEST (\n" +
                "ORG_NO varchar(4) DEFAULT NULL COMMENT '机构号'\n" +
                ",COMPY_CUST_NUM decimal(12,0) DEFAULT NULL COMMENT '公司客户数'\n" +
                ")DISTRIBUTED BY ('ORG_NO')\n" +
                "comment '公司客户数'\n" +
                ";";
        String insertSelect = "INSERT INTO {PID}.VT_GBASE_TEST " +
                "SELECT OPEN_ACCT_ORG_NO, COUNT(DISTINCT CUST_NO) " +
                "FROM {CDM}.C_AGT_DEPOSIT_ACCT_INF T";

        List<String> blocks = Arrays.asList(createTemp, insertSelect);
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "PID.TARGET");

        List<FieldMapping> mappings = result.getFieldMappings();
        assertFalse(mappings.isEmpty(), "应提取到字段映射（CREATE TEMP 块解析成功）, 实际 mappings=" + mappings.size());
        // INSERT 临时表 targetField 应为 CREATE TEMP 定义的真实列名
        boolean hasOrgNo = mappings.stream()
                .anyMatch(fm -> "org_no".equals(fm.getTargetField()));
        assertTrue(hasOrgNo, "targetField 应包含真实列名 org_no, 实际: "
                + mappings.stream().map(FieldMapping::getTargetField).toList());
        boolean hasCompyCustNum = mappings.stream()
                .anyMatch(fm -> "compy_cust_num".equals(fm.getTargetField()));
        assertTrue(hasCompyCustNum, "targetField 应包含真实列名 compy_cust_num, 实际: "
                + mappings.stream().map(FieldMapping::getTargetField).toList());
        // 不应出现 expr_N 占位
        boolean hasExprPlaceholder = mappings.stream()
                .anyMatch(fm -> fm.getTargetField() != null && fm.getTargetField().startsWith("expr_"));
        assertFalse(hasExprPlaceholder, "不应出现 expr_N 占位列名（GBase 语法应被清理）, 实际: "
                + mappings.stream().map(FieldMapping::getTargetField).toList());
    }

    @Test
    void testConditionFieldsAttachedToEdges() {
        String sql = "INSERT INTO {CDM}.TARGET SELECT a.id FROM {FDM}.F_A a " +
                "JOIN {FDM}.F_B b ON a.cust_id = b.id WHERE b.balance > 0";
        List<String> blocks = Arrays.asList(sql);

        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        assertEquals(2, result.getEdges().size());
        // b.id(join) / b.balance(where) 归属 f_b 边；a.cust_id(join) 归属 f_a 边
        LineageEdge fBEdge = result.getEdges().stream()
                .filter(e -> "f_b".equals(e.getSourceTableShort())).findFirst().orElse(null);
        assertNotNull(fBEdge, "应有 f_b 源表边");
        assertNotNull(fBEdge.getConditionFields());
        assertTrue(fBEdge.getConditionFields().stream().anyMatch(cf ->
                        "id".equals(cf.getSourceField()) && "join_condition".equals(cf.getRole())),
                "b.id 应为 f_b 边的 join_condition");
        assertTrue(fBEdge.getConditionFields().stream().anyMatch(cf ->
                        "balance".equals(cf.getSourceField()) && "where_condition".equals(cf.getRole())),
                "b.balance 应为 f_b 边的 where_condition");

        LineageEdge fAEdge = result.getEdges().stream()
                .filter(e -> "f_a".equals(e.getSourceTableShort())).findFirst().orElse(null);
        assertNotNull(fAEdge, "应有 f_a 源表边");
        assertTrue(fAEdge.getConditionFields().stream().anyMatch(cf ->
                        "cust_id".equals(cf.getSourceField()) && "join_condition".equals(cf.getRole())),
                "a.cust_id 应为 f_a 边的 join_condition");
    }

    /**
     * P1 场景：派生表分支内多表 JOIN + 裸列引用（SUM(loan_bal)）。
     * 复现 dwa_pid_a_g_ln_new_pord_sum 断链：
     * 分支 FROM 主表 a + JOIN 两个子查询（b/c 映射 null）→ 分支 aliasMap.size()>1
     * → 单表兜底不成立 → 裸列 loan_bal 无归属 → 分支索引无 risk_bal 条目
     * → 外层 t.risk_bal 穿透失败 → 断链。
     * 修复：indexBranchColumns 对多表分支裸列做 DDL 唯一匹配
     * （临时表 CREATE TEMP 列定义 + 物理表元数据），仅唯一命中时归属。
     */
    @Test
    void testBranchBareColumnUniqueMatch_InDerivedTable() {
        schemaVarMap.put("PID", "etl.dwa_db_pid");
        String createLoan = "CREATE TEMPORARY TABLE {PID}.VT_P1_LOAN (\n" +
                "CUST_NO varchar(64),\n" +
                "MANAGER_ORG_NO varchar(64),\n" +
                "LOAN_BAL decimal(18,2)\n" +
                ")";
        String createObj = "CREATE TEMPORARY TABLE {PID}.VT_P1_OBJ (\n" +
                "OBJ_NO varchar(64)\n" +
                ")";
        String insertSelect = "INSERT INTO {CDM}.c_p1_sum (org_no, risk_bal) " +
                "SELECT t.org_no, t.risk_bal " +
                "FROM (select manager_org_no as org_no, sum(loan_bal) as risk_bal " +
                "      from {PID}.VT_P1_LOAN a " +
                "      left join (select OBJ_NO from {PID}.VT_P1_OBJ group by OBJ_NO) b " +
                "        on a.CUST_NO = b.OBJ_NO " +
                "      group by manager_org_no) t";

        List<String> blocks = Arrays.asList(createLoan, createObj, insertSelect);
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        FieldMapping riskBal = result.getFieldMappings().stream()
                .filter(fm -> "risk_bal".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(riskBal, "应存在 risk_bal 映射");
        boolean riskResolved = riskBal.getInputs() != null && riskBal.getInputs().stream()
                .anyMatch(ti -> "vt_p1_loan".equals(ti.getSourceTableShort())
                        && "loan_bal".equals(ti.getSourceField()));
        assertTrue(riskResolved, "分支裸列 sum(loan_bal) 唯一匹配后 t.risk_bal 应穿透归属 vt_p1_loan.loan_bal, 实际 inputs="
                + (riskBal.getInputs() == null ? "null" : riskBal.getInputs().stream()
                        .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));

        FieldMapping orgNo = result.getFieldMappings().stream()
                .filter(fm -> "org_no".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(orgNo, "应存在 org_no 映射");
        boolean orgResolved = orgNo.getInputs() != null && orgNo.getInputs().stream()
                .anyMatch(ti -> "vt_p1_loan".equals(ti.getSourceTableShort())
                        && "manager_org_no".equals(ti.getSourceField()));
        assertTrue(orgResolved, "分支裸列 manager_org_no 唯一匹配后 t.org_no 应穿透归属 vt_p1_loan.manager_org_no, 实际 inputs="
                + (orgNo.getInputs() == null ? "null" : orgNo.getInputs().stream()
                        .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));
    }

    /**
     * P2 场景：派生表分支内常量别名列（'0098' AS BRCH_NO / 0 AS TERM_END_BAL）被外层引用。
     * 复现 dwa_c_cus_ent_wechat_cust_rela / dwa_pid_a_d_ln_loan_specil_item_setou_dtl 断链：
     * 分支索引条目由列引用驱动创建, 纯字面量项无列引用 → 索引无该列条目
     * → 外层穿透失败 → 留下 sourceTableShort=null 的 input（伪断链）。
     * 修复：字面量项登记 __constant__ 标记; 穿透命中常量来源时丢弃该 input
     * （常量无上游列, 不构成断链; 真实列引用不受影响）。
     */
    @Test
    void testConstantAliasColumn_PassthroughDropsInput() {
        schemaVarMap.put("PID", "etl.dwa_db_pid");
        String insertSelect = "INSERT INTO {CDM}.c_p2_rela (org_no, brch_no, bal) " +
                "SELECT a.org_no, " +
                "       CASE WHEN a.ent_wechat_rela_create_org_no = '' THEN brch_no " +
                "            ELSE a.ent_wechat_rela_create_org_no END, " +
                "       term_end_bal " +
                "FROM (select org_no, '0098' as ent_wechat_rela_create_org_no, " +
                "             '总行' as brch_no, 0 as term_end_bal " +
                "      from {FDM}.f_test_nometab_b) a";

        List<String> blocks = Collections.singletonList(insertSelect);
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        // 真实列 org_no 仍应正常穿透归属单表
        FieldMapping orgNo = result.getFieldMappings().stream()
                .filter(fm -> "org_no".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(orgNo, "应存在 org_no 映射");
        assertTrue(orgNo.getInputs() != null && orgNo.getInputs().stream()
                        .anyMatch(ti -> "f_test_nometab_b".equals(ti.getSourceTableShort())),
                "org_no 应穿透归属 f_test_nometab_b, 实际 inputs="
                        + (orgNo.getInputs() == null ? "null" : orgNo.getInputs().stream()
                                .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));

        // 常量来源列（brch_no / term_end_bal / ent_wechat_rela_create_org_no）不应留下 null 表 input
        List<FieldMapping> constTargets = result.getFieldMappings().stream()
                .filter(fm -> "brch_no".equals(fm.getTargetField()) || "bal".equals(fm.getTargetField()))
                .toList();
        assertEquals(2, constTargets.size(), "应存在 brch_no 和 bal 映射");
        for (FieldMapping fm : constTargets) {
            boolean hasNullTableInput = fm.getInputs() != null && fm.getInputs().stream()
                    .anyMatch(ti -> ti.getSourceTableShort() == null || ti.getSourceTableShort().isEmpty());
            assertFalse(hasNullTableInput, "常量来源列 " + fm.getTargetField()
                    + " 穿透后不应残留 null 表 input（应按常量丢弃）, 实际 inputs="
                    + (fm.getInputs() == null ? "null" : fm.getInputs().stream()
                            .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));
        }
    }

    /**
     * P3 场景一（顶层）：INSERT SELECT 的 SELECT 项为 A.*，A 是主 FROM 子查询别名。
     * 复现 loan_appl_inf 等 proc 中 `select A.* from (...) A` 形态断链：
     * 修复前：A.* 是 AllTableColumns 表达式，collectColumnRefs 不产出列引用
     * → 每个 targetField 无 inputs（断链），且仅第 1 个目标列得到映射。
     * 修复：顶层展开 A.* 为子查询具体列（A.col1, A.col2 ...），复用既有穿透链路。
     */
    @Test
    void testSubqueryAliasStar_TopLevelExpandedToConcreteColumns() {
        schemaVarMap.put("PID", "pid");
        String insertSelect = "INSERT INTO {PID}.a_p3_loan (loan_no, cust_no) " +
                "SELECT A.* " +
                "FROM ( " +
                "  SELECT T1.loan_no, T2.cust_no " +
                "  FROM {FDM}.f_p3_loan T1 JOIN {FDM}.f_p3_cust T2 ON T1.cust_no = T2.cust_no " +
                ") A";

        List<String> blocks = Collections.singletonList(insertSelect);
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "PID.TARGET");

        List<FieldMapping> mappings = result.getFieldMappings();
        assertEquals(2, mappings.size(), "A.* 展开后应按子查询列数产生 2 条映射, 实际: "
                + mappings.stream().map(FieldMapping::getTargetField).toList());

        FieldMapping loanNo = mappings.stream()
                .filter(fm -> "loan_no".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(loanNo, "应存在 loan_no 映射");
        assertTrue(loanNo.getInputs() != null && loanNo.getInputs().stream()
                        .anyMatch(ti -> "f_p3_loan".equals(ti.getSourceTableShort())
                                && "loan_no".equals(ti.getSourceField())),
                "穿透后 loan_no 的 inputs 应含 f_p3_loan.loan_no, 实际 inputs="
                        + (loanNo.getInputs() == null ? "null" : loanNo.getInputs().stream()
                                .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));

        FieldMapping custNo = mappings.stream()
                .filter(fm -> "cust_no".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(custNo, "应存在 cust_no 映射");
        assertTrue(custNo.getInputs() != null && custNo.getInputs().stream()
                        .anyMatch(ti -> "f_p3_cust".equals(ti.getSourceTableShort())
                                && "cust_no".equals(ti.getSourceField())),
                "穿透后 cust_no 的 inputs 应含 f_p3_cust.cust_no, 实际 inputs="
                        + (custNo.getInputs() == null ? "null" : custNo.getInputs().stream()
                                .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));
    }

    /**
     * P3 场景二（嵌套）：中层子查询 SELECT 项为 A.*，A 是内层子查询别名，
     * 外层再通过 B.col 引用中层子查询列。
     * 复现深层嵌套 proc 断链：中层 A.* 不展开 → 外层 scope 无列索引 → B.col 穿透失败。
     * 修复：indexBranchColumns 遇 A.* 前缀为子查询别名时，将内层已索引列冒泡到当前 scope。
     */
    @Test
    void testNestedSubqueryAliasStar_BubbleInnerColumns() {
        schemaVarMap.put("PID", "pid");
        String insertSelect = "INSERT INTO {PID}.a_p3_np (np_ind) " +
                "SELECT B.np_ind " +
                "FROM ( " +
                "  SELECT A.* " +
                "  FROM ( " +
                "    SELECT CASE WHEN s.fiveclass_class_cd IN ('03','04') THEN 'Y' ELSE 'N' END AS NP_IND " +
                "    FROM {CDM}.c_agt_credit_card_acct_stand s " +
                "  ) A " +
                ") B";

        List<String> blocks = Collections.singletonList(insertSelect);
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "PID.TARGET");

        List<FieldMapping> mappings = result.getFieldMappings();
        assertFalse(mappings.isEmpty(), "应提取到字段映射");

        FieldMapping npInd = mappings.stream()
                .filter(fm -> "np_ind".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(npInd, "应存在 np_ind 映射, 实际: "
                + mappings.stream().map(FieldMapping::getTargetField).toList());

        assertTrue(npInd.getInputs() != null && npInd.getInputs().stream()
                        .anyMatch(ti -> "c_agt_credit_card_acct_stand".equals(ti.getSourceTableShort())
                                && "fiveclass_class_cd".equals(ti.getSourceField())),
                "冒泡+穿透后 np_ind 的 inputs 应含 c_agt_credit_card_acct_stand.fiveclass_class_cd, 实际 inputs="
                        + (npInd.getInputs() == null ? "null" : npInd.getInputs().stream()
                                .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));
    }

    /**
     * P4 场景：CREATE TEMP 的 AS (SELECT ...) 括号形式（含 DISTRIBUTED BY 子句）。
     * 复现 dwa_c_cus_pub_cust_basic_inf.py L205-209 等：
     * CREATE TABLE {CDM}.VT_X DISTRIBUTED BY('col') as (SELECT ... FROM ...)
     * 修复前：JSqlParser 解析成功但 getSelectBody() 返回 ParenthesedSelect 而非 PlainSelect
     * → sourceSelect=null → 无源表 edge、无字段映射、无 CTAS 派生列缓存 → 后续引用断链。
     */
    @Test
    void testCreateTempAsParenthesizedSelect_ExtractsEdgesAndMappings() {
        schemaVarMap.put("CDM", "cdm");
        String createTemp = "CREATE TABLE {CDM}.vt_p4_x DISTRIBUTED BY('cust_no') as " +
                "(SELECT cust_no, cust_nm FROM {FDM}.f_p4_src)";
        String insertSelect = "INSERT INTO {CDM}.c_p4_t (cust_no, cust_nm) " +
                "SELECT cust_no, cust_nm FROM {CDM}.vt_p4_x";

        List<String> blocks = Arrays.asList(createTemp, insertSelect);
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "CDM.TARGET");

        // 1. CREATE 块产生 edge: f_p4_src → vt_p4_x
        boolean hasCtasEdge = result.getEdges().stream().anyMatch(e ->
                "f_p4_src".equals(e.getSourceTableShort())
                        && "vt_p4_x".equals(e.getTargetTableShort())
                        && "CREATE TEMP".equals(e.getOperation())
                        && e.isTargetIsTemp());
        assertTrue(hasCtasEdge, "AS (SELECT) 括号形式的 CREATE TEMP 应产生 f_p4_src → vt_p4_x edge, 实际 edges="
                + result.getEdges().stream()
                        .map(e -> e.getSourceTableShort() + "->" + e.getTargetTableShort()
                                + "/" + e.getOperation()).toList());

        // 2. INSERT 块 edge: vt_p4_x → c_p4_t（CTAS 块被解析后 vt_p4_x 作为源表出现）
        boolean hasInsertEdge = result.getEdges().stream().anyMatch(e ->
                "vt_p4_x".equals(e.getSourceTableShort())
                        && "c_p4_t".equals(e.getTargetTableShort())
                        && "INSERT SELECT".equals(e.getOperation()));
        assertTrue(hasInsertEdge, "后续 INSERT 块应产生 vt_p4_x → c_p4_t edge, 实际 edges="
                + result.getEdges().stream()
                        .map(e -> e.getSourceTableShort() + "->" + e.getTargetTableShort()
                                + "/" + e.getOperation()).toList());

        // 3. CTAS 块字段映射: cust_no ← f_p4_src.cust_no
        FieldMapping custNo = result.getFieldMappings().stream()
                .filter(fm -> "cust_no".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(custNo, "应存在 cust_no 映射, 实际: "
                + result.getFieldMappings().stream().map(FieldMapping::getTargetField).toList());
        assertTrue(custNo.getInputs() != null && custNo.getInputs().stream()
                        .anyMatch(ti -> "f_p4_src".equals(ti.getSourceTableShort())
                                && "cust_no".equals(ti.getSourceField())),
                "CTAS 字段映射 cust_no 的 inputs 应含 f_p4_src.cust_no, 实际 inputs="
                        + (custNo.getInputs() == null ? "null" : custNo.getInputs().stream()
                                .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));
    }

    /**
     * P5 场景：多表 JOIN 引用分区后缀物理表（基表 + 数字后缀, 如 a_d_fin_mkt_prft_loss_dtl_10）。
     * 复现 dwa_pid_a_d_dm_credit_card_org_in 断链：
     * table_column_meta 只有基表 a_d_fin_mkt_prft_loss_dtl 元数据（32列）,
     * 后缀表 _10/_11/_12/_13/_15 查无记录 → 被剔除出裸列唯一匹配候选
     * → SELECT 裸列 data_dt 无归属 → 断链。
     * 修复：buildDdlCache 查无元数据时剥离分区后缀（_\d+ / 尾 _）回退基表列（补录到原名 key）。
     */
    @Test
    void testPartitionSuffixTable_BareColumnFallsBackToBaseTableColumns() {
        schemaVarMap.put("PID", "pid");
        String insertSelect = "INSERT INTO {PID}.a_p5_t (data_dt, teller_no) " +
                "SELECT data_dt, T2.teller_no " +
                "FROM {PID}.a_d_fin_mkt_prft_loss_dtl_10 T1 " +
                "JOIN {PID}.f_pt_crbs_teller_param T2 ON T1.data_dt = T2.teller_no";

        List<String> blocks = Collections.singletonList(insertSelect);
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "PID.TARGET");

        FieldMapping dataDt = result.getFieldMappings().stream()
                .filter(fm -> "data_dt".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(dataDt, "应存在 data_dt 映射, 实际: "
                + result.getFieldMappings().stream().map(FieldMapping::getTargetField).toList());

        // 裸列 data_dt 应通过基表列回退唯一匹配到分区后缀表（表名保留原名）
        boolean resolved = dataDt.getInputs() != null && dataDt.getInputs().stream()
                .anyMatch(ti -> "a_d_fin_mkt_prft_loss_dtl_10".equals(ti.getSourceTableShort())
                        && "data_dt".equals(ti.getSourceField()));
        assertTrue(resolved, "分区后缀表裸列 data_dt 应回退基表元数据唯一匹配归属 "
                        + "a_d_fin_mkt_prft_loss_dtl_10.data_dt, 实际 inputs="
                        + (dataDt.getInputs() == null ? "null" : dataDt.getInputs().stream()
                                .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));

        // 对照：显式别名列不受影响
        FieldMapping tellerNo = result.getFieldMappings().stream()
                .filter(fm -> "teller_no".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(tellerNo, "应存在 teller_no 映射");
        assertTrue(tellerNo.getInputs() != null && tellerNo.getInputs().stream()
                        .anyMatch(ti -> "f_pt_crbs_teller_param".equals(ti.getSourceTableShort())
                                && "teller_no".equals(ti.getSourceField())),
                "显式别名列 T2.teller_no 应归属 f_pt_crbs_teller_param, 实际 inputs="
                        + (tellerNo.getInputs() == null ? "null" : tellerNo.getInputs().stream()
                                .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()));
    }

    /**
     * P7 场景：dwa_pid_a_g_dp_deposit_report 结构复现。
     * CREATE TABLE（非 TEMPORARY 关键字）+ GBase 双引号内联 DDL + TABLESPACE 私有选项 + 占位符表名后缀，
     * 后续 INSERT 从该临时表取数（裸列）。
     * 修复前：预处理后 JSqlParser 在 TABLESPACE 处解析失败 → CREATE 块无列定义入缓存
     * → 后续 INSERT 裸列无法归属 → source_table_short=NULL 断链（53 条）。
     * 修复后：TABLESPACE 选项剥离 + 双引号列名归一化 → 列定义入 tempColumnCache → 裸列唯一匹配。
     */
    @Test
    void testCreateTableQuotedDdl_TablespaceOption_BareColumnResolves() {
        schemaVarMap.put("PID", "pid");
        String createTable = "CREATE TABLE pid.vt_p7_rpt_02_{ETL_TX_DT} (\n" +
                "  \"DATA_DT\" date NOT NULL COMMENT '数据日期',\n" +
                "  \"BAL\" decimal(26,6) DEFAULT NULL COMMENT '余额'\n" +
                ") ENGINE=EXPRESS DEFAULT CHARSET=utf8 TABLESPACE='sys_tablespace' COMMENT='临时表'";
        String insertToTemp = "INSERT INTO {PID}.vt_p7_rpt_02_ (data_dt, bal) " +
                "SELECT T.data_dt, T.bal FROM {FDM}.f_dep_acct T";
        String insertFromTemp = "INSERT INTO {PID}.a_p7_t (data_dt, bal) " +
                "SELECT data_dt, bal FROM {PID}.vt_p7_rpt_02_";

        List<String> blocks = Arrays.asList(createTable, insertToTemp, insertFromTemp);
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "PID.TARGET");

        FieldMapping dataDt = result.getFieldMappings().stream()
                .filter(fm -> "data_dt".equals(fm.getTargetField()))
                .filter(fm -> {
                    return fm.getInputs() != null && fm.getInputs().stream()
                            .anyMatch(ti -> "vt_p7_rpt_02_".equals(ti.getSourceTableShort()));
                })
                .findFirst().orElse(null);
        assertNotNull(dataDt, "INSERT 自临时表的 data_dt 映射应归属 vt_p7_rpt_02_（引号 DDL 列定义入缓存）, 实际: "
                + result.getFieldMappings().stream().map(FieldMapping::getTargetField).toList());

        boolean resolved = dataDt.getInputs().stream()
                .anyMatch(ti -> "vt_p7_rpt_02_".equals(ti.getSourceTableShort())
                        && "data_dt".equals(ti.getSourceField()));
        assertTrue(resolved, "裸列 data_dt 应唯一匹配引号内联列定义, 实际 inputs="
                + dataDt.getInputs().stream()
                        .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList());
    }

    /**
     * P8 场景：dwa_pid_a_d_dm_credit_card_org_in block 24/25 结构复现。
     * CTAS 顶层 SELECT * FROM (SELECT ... AS 别名 ...) t —— 临时表列由内层子查询别名经裸星号透传。
     * 修复前：裸 * 被 hasWildcard 早退吞掉（P3 只处理 A.* 带前缀形态）→ CTAS 0 条字段映射
     * → 派生列不入 tempColumnCache → 后续 INSERT 裸列断链（26 条）。
     * 修复后：FROM 为单子查询时裸 * 展开为内层输出列 → 派生列入缓存 → 裸列唯一匹配归属。
     */
    @Test
    void testCtasSelectStarFromSubquery_RegistersDerivedColumnsForLaterBareColumns() {
        schemaVarMap.put("PID", "pid");
        String createTemp = "create temporary table {PID}.vt_p8_t13 select * from (" +
                "SELECT DATE('{ETL_TX_DT}') AS data_dt, 'x' AS bil_flow_big, T1.bal AS bal " +
                "FROM {FDM}.f_dep_acct T1) t";
        String insertFromTemp = "INSERT INTO {PID}.a_p8_t (data_dt, bil_flow_big, bal) " +
                "SELECT data_dt, bil_flow_big, bal FROM {PID}.vt_p8_t13 t1 " +
                "LEFT JOIN {FDM}.f_pt_crbs_teller_param t2 ON t1.bal = t2.teller_no";

        List<String> blocks = Arrays.asList(createTemp, insertFromTemp);
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "PID.TARGET");

        // 断言1: CTAS 裸星号展开后产生字段映射（targetField 为内层别名, 归属临时表）
        boolean ctasMappingExists = result.getFieldMappings().stream()
                .anyMatch(fm -> "bil_flow_big".equals(fm.getTargetField())
                        && fm.getInputs() != null
                        && fm.getInputs().stream().anyMatch(ti -> "vt_p8_t13".equals(ti.getSourceTableShort())));
        assertTrue(ctasMappingExists, "CTAS SELECT * 应展开内层别名列为字段映射并归属临时表, 实际: "
                + result.getFieldMappings().stream()
                        .map(fm -> fm.getTargetField() + "<-"
                                + (fm.getInputs() == null ? "null" : fm.getInputs().stream()
                                        .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()))
                        .toList());

        // 断言2: 后续 INSERT 裸列 bil_flow_big 应唯一匹配临时表缓存列 → 归属 vt_p8_t13（消除断链）
        boolean insertResolved = result.getFieldMappings().stream()
                .filter(fm -> "bil_flow_big".equals(fm.getTargetField()))
                .anyMatch(fm -> fm.getInputs() != null && fm.getInputs().stream()
                        .anyMatch(ti -> "vt_p8_t13".equals(ti.getSourceTableShort())
                                && "bil_flow_big".equals(ti.getSourceField())));
        assertTrue(insertResolved, "INSERT 裸列 bil_flow_big 应唯一匹配临时表缓存列归属 vt_p8_t13.bil_flow_big, 实际: "
                + result.getFieldMappings().stream()
                        .filter(fm -> "bil_flow_big".equals(fm.getTargetField()))
                        .map(fm -> (fm.getInputs() == null ? "null" : fm.getInputs().stream()
                                .map(ti -> ti.getSourceTableShort() + "." + ti.getSourceField()).toList()))
                        .toList());
    }

    /**
     * P9 场景：dwa_pid_a_d_ln_loan_org_dtl block 6 断链复现（多子查询 scope 裸列穿透）。
     * 真实根因：表达式裸引用派生表聚合输出别名 exchange_to_cny_rate（a1），同时块内存在多个子查询 scope
     * → applySubqueryColumnPassthrough 仅在 subqueryIndex.size()==1 时承接裸列 → 穿透失败保持 NULL 断链。
     * 修复：裸列 + 多 scope 时按列名在所有 scope 中唯一命中才归属（保守穿透）。
     */
    @Test
    void testBareColumnWithMultipleSubqueryScopes_ResolvesByUniqueScopeHit() {
        schemaVarMap.put("PID", "pid");
        // CREATE TEMP 定义临时表列（同真实 block 3）
        String createTemp = "CREATE TEMPORARY TABLE {PID}.vt_p9_01_{ETL_TX_DT} (data_dt varchar(10), " +
                "org_no varchar(20), ftpexpen decimal(18,2))";
        // 真实 block 6 形态: 裸列 ftpexpen(临时表列) + 裸列 exchange_to_cny_rate(a1 聚合别名) + 两个子查询 scope
        String insertUse = "INSERT INTO {PID}.a_p9_t (ftp_calc) " +
                "SELECT NVL(ftpexpen * nvl(exchange_to_cny_rate, nvl(currcy.dom_currcy_convt_price, 1)), 0) " +
                "FROM {PID}.vt_p9_01_ A " +
                "LEFT JOIN (select t.acct_no, max(t.exchange_to_cny_rate) exchange_to_cny_rate " +
                "           from PID.a_d_p9_prft t group by t.acct_no) a1 ON A.org_no = a1.acct_no " +
                "LEFT JOIN (select t2.org_no, t2.org_level from FDM.f_p9_org t2) d ON A.org_no = d.org_no " +
                "LEFT JOIN cdm.c_com_exch_rate currcy ON A.org_no = currcy.currcy_cd";

        List<String> blocks = Arrays.asList(createTemp, insertUse);
        LineageParseResult result = lineageExtractor.extract(blocks, schemaVarMap, "PID.TARGET");

        FieldMapping ftpCalc = result.getFieldMappings().stream()
                .filter(fm -> "ftp_calc".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(ftpCalc, "应存在 ftp_calc 映射, 实际: "
                + result.getFieldMappings().stream().map(FieldMapping::getTargetField).toList());
        // 断言 1: 裸列 exchange_to_cny_rate 应经 a1 scope 穿透归属底层物理表 a_d_p9_prft
        boolean exchResolved = ftpCalc.getInputs() != null && ftpCalc.getInputs().stream()
                .anyMatch(ti -> "a_d_p9_prft".equals(ti.getSourceTableShort())
                        && "exchange_to_cny_rate".equals(ti.getSourceField()));
        assertTrue(exchResolved, "裸列 exchange_to_cny_rate 应按列名唯一命中 a1 scope 穿透归属 a_d_p9_prft, 实际 inputs="
                + (ftpCalc.getInputs() == null ? "null" : ftpCalc.getInputs().stream()
                        .map(ti -> IFNULL(ti)).toList()));
        // 断言 2: 所有 input 均应归属（无 NULL 断链）
        List<String> unresolved = ftpCalc.getInputs() == null ? List.of("inputs=null")
                : ftpCalc.getInputs().stream()
                        .filter(ti -> ti.getSourceTableShort() == null || ti.getSourceTableShort().isEmpty())
                        .map(ti -> ti.getSourceField()).toList();
        assertTrue(unresolved.isEmpty(), "不应存在无归属 input, 实际未归属: " + unresolved);
    }

    @Test
    void testBareColumnFromJoinSubqueryScope_AggregateOutputPassthrough() {
        schemaVarMap.put("PID", "pid");
        // 复现 new_pord_sum block 3 形态: 外层 t.int_income → 内层 sum(int_income) 裸列 →
        // JOIN 子查询 c 的聚合输出列 sum(m1.acc_int_year*d.dom_currcy_convt_price) AS int_income
        String insert = "INSERT INTO PID.a_p10_t (int_income) " +
                "SELECT t.int_income " +
                "FROM ( " +
                "  SELECT t1.prod_no, sum(int_income) AS int_income " +
                "  FROM PID.f_p10_rcpt t1 " +
                "  LEFT JOIN (SELECT t_account_number, sum(m1.acc_int_year * d.dom_currcy_convt_price) AS int_income " +
                "             FROM FDM.f_p10_cust m1 " +
                "             LEFT JOIN CDM.c_p10_exch d ON m1.iso_cd = d.currcy_cd " +
                "             GROUP BY t_account_number) c " +
                "    ON t1.rcpt_no = c.t_account_number " +
                "  GROUP BY t1.prod_no " +
                ") t";

        LineageParseResult result = lineageExtractor.extract(List.of(insert), schemaVarMap, "PID.A_P10_T");

        FieldMapping intIncome = result.getFieldMappings().stream()
                .filter(fm -> "int_income".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(intIncome, "应存在 int_income 映射, 实际: "
                + result.getFieldMappings().stream().map(FieldMapping::getTargetField).toList());
        // 断言 1: 裸列 int_income 应在 JOIN 子查询 c scope 唯一命中, 穿透到 f_p10_cust.acc_int_year
        boolean accResolved = intIncome.getInputs() != null && intIncome.getInputs().stream()
                .anyMatch(ti -> "f_p10_cust".equals(ti.getSourceTableShort())
                        && "acc_int_year".equals(ti.getSourceField()));
        assertTrue(accResolved, "裸列 int_income 应唯一命中 c scope 穿透归属 f_p10_cust.acc_int_year, 实际 inputs="
                + (intIncome.getInputs() == null ? "null" : intIncome.getInputs().stream()
                        .map(ti -> IFNULL(ti)).toList()));
        // 断言 2: 无 NULL 断链
        List<String> unresolved = intIncome.getInputs() == null ? List.of("inputs=null")
                : intIncome.getInputs().stream()
                        .filter(ti -> ti.getSourceTableShort() == null || ti.getSourceTableShort().isEmpty())
                        .map(ti -> ti.getSourceField()).toList();
        assertTrue(unresolved.isEmpty(), "不应存在无归属 input, 实际未归属: " + unresolved);
    }

    @Test
    void testDateLiteralAliasColumn_PassthroughDropsConstantInput() {
        schemaVarMap.put("PID", "pid");
        // 复现 credit_card_acct_sum block 2: 内层首项 DATE'{ETL_TX_DT}' AS DATA_DT（DATE 与引号无空格）,
        // 外层 T1.DATA_DT 引用该常量别名列 → 应识别为常量并丢弃 input, 而非留 NULL 断链
        String insert = "INSERT INTO PID.a_p11_t (data_dt, org_no) " +
                "SELECT T1.DATA_DT AS DATA_DT, T1.ORG_NO AS ORG_NO " +
                "FROM ( " +
                "  SELECT DATE'{ETL_TX_DT}' AS DATA_DT, t1.ORG_NO AS ORG_NO " +
                "  FROM CDM.f_p11_card t1 " +
                "  LEFT JOIN (select org_no from FDM.f_p11_ref group by org_no) r ON t1.org_no = r.org_no " +
                "  GROUP BY t1.ORG_NO " +
                ") T1";

        LineageParseResult result = lineageExtractor.extract(List.of(insert), schemaVarMap, "PID.A_P11_T");

        FieldMapping dataDt = result.getFieldMappings().stream()
                .filter(fm -> "data_dt".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(dataDt, "应存在 data_dt 映射, 实际: "
                + result.getFieldMappings().stream().map(FieldMapping::getTargetField).toList());
        // 断言: data_dt 源头是 DATE 常量字面量, 应丢弃 input（无上游列, 不构成断链）
        List<String> unresolved = dataDt.getInputs() == null ? List.of()
                : dataDt.getInputs().stream()
                        .filter(ti -> ti.getSourceTableShort() == null || ti.getSourceTableShort().isEmpty())
                        .map(ti -> ti.getSourceField()).toList();
        assertTrue(unresolved.isEmpty(), "DATE 常量别名列 T1.DATA_DT 不应留 NULL 断链, 实际未归属: " + unresolved);
        // 对照断言: 普通透传列 T1.ORG_NO 应正常归属
        FieldMapping orgNo = result.getFieldMappings().stream()
                .filter(fm -> "org_no".equals(fm.getTargetField()))
                .findFirst().orElse(null);
        assertNotNull(orgNo);
        boolean orgResolved = orgNo.getInputs() != null && orgNo.getInputs().stream()
                .anyMatch(ti -> "f_p11_card".equals(ti.getSourceTableShort()));
        assertTrue(orgResolved, "T1.ORG_NO 应穿透归属 f_p11_card.org_no, 实际 inputs="
                + (orgNo.getInputs() == null ? "null" : orgNo.getInputs().stream().map(ti -> IFNULL(ti)).toList()));
    }

    @Test
    void testBarePhysicalTableNames_ResolvedWithSchemaPrefixFromMeta() {
        // P12: 复现 new_pord_sum block 9: INSERT INTO A_G_LN_NEW_PORD_SUM（无 schema 前缀）,
        // 元数据 table_column_meta 中存在 pid.a_g_ln_new_pord_sum → 边的 targetTable 应补全为 PID.a_g_ln_new_pord_sum;
        // 源表裸名同理: tot_brch_check 裸读 a_g_ln_credit_card_acct_sum → 补全为 PID.a_g_ln_credit_card_acct_sum。
        // 临时表（VT_/TMP_）不补全, 保持原行为。
        schemaVarMap.put("PID", "pid");
        String insert = "INSERT INTO A_G_LN_NEW_PORD_SUM (data_dt, int_income) " +
                "SELECT t.data_dt, t.int_income " +
                "FROM (SELECT data_dt, int_income FROM a_g_ln_credit_card_acct_sum) t";

        LineageParseResult result = lineageExtractor.extract(List.of(insert), schemaVarMap, null);

        LineageEdge edge = result.getEdges().stream()
                .filter(e -> "a_g_ln_new_pord_sum".equals(e.getTargetTableShort()))
                .findFirst().orElse(null);
        assertNotNull(edge, "应存在目标表 a_g_ln_new_pord_sum 的边");
        assertEquals("PID.a_g_ln_new_pord_sum", edge.getTargetTable(),
                "裸名物理目标表应从元数据补全 schema 前缀, 实际: " + edge.getTargetTable());
        assertEquals("PID.a_g_ln_credit_card_acct_sum", edge.getSourceTable(),
                "裸名物理源表应从元数据补全 schema 前缀, 实际: " + edge.getSourceTable());
    }

    private String IFNULL(TransformInput ti) {
        return (ti.getSourceTableShort() == null ? "NULL" : ti.getSourceTableShort()) + "." + ti.getSourceField();
    }
}
