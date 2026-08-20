package com.hb.extractor.parser;

import com.hb.extractor.ProcExtractorApplication;
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
}
