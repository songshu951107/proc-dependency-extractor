package com.hb.extractor.parser;

import com.hb.extractor.ProcExtractorApplication;
import com.hb.extractor.parser.dto.ConditionField;
import com.hb.extractor.parser.dto.FieldMapping;
import com.hb.extractor.parser.dto.LineageEdge;
import com.hb.extractor.parser.dto.LineageParseResult;
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
}
