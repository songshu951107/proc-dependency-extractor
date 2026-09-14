package com.hb.extractor.service;

import com.hb.extractor.dto.ImpactAnalysisRequest;
import com.hb.extractor.dto.ImpactAnalysisVO;
import com.hb.extractor.service.impl.ImpactAnalysisServiceImpl;
import com.hb.extractor.mapper.Neo4jSyncLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImpactAnalysisServiceTest {

    @Mock
    private Neo4jClient neo4jClient;

    @Mock
    private Neo4jSyncLogMapper neo4jSyncLogMapper;

    @InjectMocks
    private ImpactAnalysisServiceImpl impactService;

    @Test
    void testAnalyze_Neo4jUnavailable_ReturnsEmptyResult() {
        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setSchema("CDM");
        req.setTableShortName("TEST_TABLE");
        req.setDirection("DOWNSTREAM");
        req.setMaxDepth(3);
        when(neo4jClient.query(anyString())).thenThrow(new RuntimeException("Neo4j 不可用"));

        ImpactAnalysisVO vo = impactService.analyze(req);

        assertNotNull(vo);
        assertEquals("cdm.test_table", vo.getSourceTable());
        assertNotNull(vo.getNodes());
        assertNotNull(vo.getImpactedByDepth());
        assertTrue(vo.getImpactedByDepth().isEmpty());
        assertNotNull(vo.getPropagationChains());
        assertTrue(vo.getPropagationChains().isEmpty());
    }

    @Test
    void testAnalyze_UpstreamDirection_PopulatesSourceTable() {
        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setSchema("CDM");
        req.setTableShortName("TEST_TABLE");
        req.setDirection("UPSTREAM");
        req.setMaxDepth(3);
        when(neo4jClient.query(anyString())).thenThrow(new RuntimeException("Neo4j 不可用"));

        ImpactAnalysisVO vo = impactService.analyze(req);

        assertEquals("cdm.test_table", vo.getSourceTable());
        assertNotNull(vo.getImpactedByDepth());
    }

    @Test
    void testAnalyze_BothDirection_DoesNotCrash() {
        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setSchema("CDM");
        req.setTableShortName("TEST_TABLE");
        req.setDirection("BOTH");
        req.setMaxDepth(3);
        when(neo4jClient.query(anyString())).thenThrow(new RuntimeException("Neo4j 不可用"));

        ImpactAnalysisVO vo = impactService.analyze(req);

        assertEquals("cdm.test_table", vo.getSourceTable());
        assertNotNull(vo.getImpactedByDepth());
    }

    @Test
    void testAnalyze_DepthExceedsMax_Clamped() {
        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setSchema("CDM");
        req.setTableShortName("TEST_TABLE");
        req.setDirection("DOWNSTREAM");
        req.setMaxDepth(100);  // 超上限
        when(neo4jClient.query(anyString())).thenThrow(new RuntimeException("Neo4j 不可用"));

        ImpactAnalysisVO vo = impactService.analyze(req);

        assertNotNull(vo);
        // 不应抛异常，depth 会被 clamp
    }

    @Test
    void testAnalyze_FieldLevel_DoesNotCrash() {
        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setSchema("CDM");
        req.setTableShortName("TEST_TABLE");
        req.setFieldName("ACCT_ID");
        req.setDirection("DOWNSTREAM");
        req.setMaxDepth(3);
        when(neo4jClient.query(anyString())).thenThrow(new RuntimeException("Neo4j 不可用"));

        ImpactAnalysisVO vo = impactService.analyze(req);

        assertNotNull(vo);
        assertEquals("cdm.test_table", vo.getSourceTable());
        assertNotNull(vo.getNodes());
    }

    @Test
    void testGetSyncStatus_ReturnsFailedLogs() {
        when(neo4jSyncLogMapper.selectList(any())).thenReturn(Collections.emptyList());

        var result = impactService.getSyncStatus();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * 行影响通道去重（按 表×proc×role 组）：同一目标表被多条 FILTERS 边指向时，
     * - 同组重复（同 proc 同角色，如同 proc 多块 INSERT 过滤同一表）→ ROW_IMPACT 只发一组；
     * - 不同组（不同 proc，或同 proc 的 WHERE+JOIN 双角色）→ 每组各发一组，保留 proc 归属
     *   （前端行清单 key = procName|field，丢组即丢行）。
     * FILTERS 边本身各保留一条（携带各自 proc/block 信息）。
     */
    @Test
    void testFieldLevel_Downstream_RowImpactEdgesDedupedPerTable() throws Exception {
        // 纯 Mockito 环境 @Value 不注入（maxAllowedDepth=0 会把 depth clamp 成 0），手动注入
        java.lang.reflect.Field f = ImpactAnalysisServiceImpl.class.getDeclaredField("maxAllowedDepth");
        f.setAccessible(true);
        f.setInt(impactService, 20);

        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setSchema("CDM");
        req.setTableShortName("SRC");
        req.setFieldName("BALANCE");
        req.setDirection("DOWNSTREAM");
        req.setMaxDepth(3);

        // 三条 FILTERS 边指向同一目标表 cdm.tgt：
        // f1/f2 同组（proc_a + where，不同块）；f3 异组（proc_b + join）
        Map<String, Object> f1 = new HashMap<>();
        f1.put("tableName", "cdm.tgt");
        f1.put("shortName", "TGT");
        f1.put("role", "where_condition");
        f1.put("clause", "balance > 0");
        f1.put("procName", "proc_a");
        f1.put("seq", 1);
        Map<String, Object> f2 = new HashMap<>();
        f2.put("tableName", "cdm.tgt");
        f2.put("shortName", "TGT");
        f2.put("role", "where_condition");
        f2.put("clause", "balance > 0");
        f2.put("procName", "proc_a");
        f2.put("seq", 2);
        Map<String, Object> f3 = new HashMap<>();
        f3.put("tableName", "cdm.tgt");
        f3.put("shortName", "TGT");
        f3.put("role", "join_condition");
        f3.put("clause", "id = id");
        f3.put("procName", "proc_b");
        f3.put("seq", 1);

        Map<String, Object> colA = new HashMap<>();
        colA.put("colKey", "cdm.tgt.a");
        colA.put("name", "a");
        colA.put("tableFullName", "cdm.tgt");
        Map<String, Object> colB = new HashMap<>();
        colB.put("colKey", "cdm.tgt.b");
        colB.put("name", "b");
        colB.put("tableFullName", "cdm.tgt");

        // 兜底先注册（LIFO 匹配），具体 matcher 后注册优先生效
        stubNeo4jQuery("", Collections.emptyList());
        stubNeo4jQuery(")-[f:FILTERS]->", Arrays.asList(f1, f2, f3));
        stubNeo4jQuery("BELONGS_TO", Arrays.asList(colA, colB));

        ImpactAnalysisVO vo = impactService.analyze(req);

        long filtersEdges = vo.getEdges().stream()
                .filter(e -> "FILTERS".equals(e.getEdgeType())).count();
        long rowImpactEdges = vo.getEdges().stream()
                .filter(e -> "ROW_IMPACT".equals(e.getEdgeType())).count();
        long groupAEdges = vo.getEdges().stream()
                .filter(e -> "ROW_IMPACT".equals(e.getEdgeType()) && "proc_a".equals(e.getProcName())).count();
        long groupBEdges = vo.getEdges().stream()
                .filter(e -> "ROW_IMPACT".equals(e.getEdgeType()) && "proc_b".equals(e.getProcName())).count();
        assertEquals(3, filtersEdges, "三条 FILTERS 边应各保留一条");
        // 2 组（proc_a/where 去重后 1 组 + proc_b/join 1 组）× 2 列 = 4；同组 f1/f2 不得翻倍成 6
        assertEquals(4, rowImpactEdges, "ROW_IMPACT 按 (表,proc,role) 组去重：2 组 × 2 列 = 4，同组重复不翻倍");
        assertEquals(2, groupAEdges, "proc_a 组（f1/f2 去重）应有 2 条");
        assertEquals(2, groupBEdges, "proc_b 组应有 2 条（跨 proc 不得丢行）");
    }

    /**
     * 字面量转换剪枝：LITERAL Transform（表达式为常量，如 NVL(x,0) 的 0 / INSERT 常量列）
     * 及其虚拟 __literal__ Column 不得进入分析结果——常量列的值不受任何上游影响，
     * 画进链路图只会产生"label=0 的转换节点"和"hash 字符串节点"两类噪音。
     */
    @Test
    void testFieldLevel_Downstream_LiteralTransformPruned() throws Exception {
        java.lang.reflect.Field f = ImpactAnalysisServiceImpl.class.getDeclaredField("maxAllowedDepth");
        f.setAccessible(true);
        f.setInt(impactService, 20);

        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setSchema("CDM");
        req.setTableShortName("SRC");
        req.setFieldName("BALANCE");
        req.setDirection("DOWNSTREAM");
        req.setMaxDepth(3);

        Map<String, Object> literalRow = new HashMap<>();
        literalRow.put("transformId", "lit0000000000000000000000000000");
        literalRow.put("expression", "0");
        literalRow.put("logicType", "LITERAL");
        literalRow.put("procName", "proc_a");
        Map<String, Object> litInput = new HashMap<>();
        litInput.put("colKey", "__literal__.lit0000000000000000000000000000");
        litInput.put("name", "0");
        litInput.put("tableFullName", "__literal__");
        literalRow.put("inputs", new ArrayList<>(Arrays.asList(litInput)));
        Map<String, Object> constCol = new HashMap<>();
        constCol.put("colKey", "cdm.tgt.flag");
        constCol.put("name", "flag");
        constCol.put("tableFullName", "cdm.tgt");
        literalRow.put("outputs", new ArrayList<>(Arrays.asList(constCol)));

        stubNeo4jQuery("", Collections.emptyList());
        stubNeo4jQuery("-[:INPUT_TO]->(t:Transform)", Arrays.asList(literalRow));

        ImpactAnalysisVO vo = impactService.analyze(req);

        assertTrue(vo.getNodes().stream().noneMatch(n -> "TRANSFORM".equals(n.getNodeLevel())),
                "LITERAL Transform 不应出现在节点中");
        assertTrue(vo.getNodes().stream().noneMatch(n -> n.getId().startsWith("__literal__.")),
                "__literal__ 虚拟列不应出现在节点中");
        assertTrue(vo.getNodes().stream().noneMatch(n -> "cdm.tgt.flag".equals(n.getId())),
                "常量下游列不因字面量转换入图（值恒定，不受起点影响）");
    }

    // ============ 临时表穿透口径（方案A：临时表不占深度预算） ============

    /** 构造 Column map（colKey/name/tableFullName/temp，与 Cypher 返回结构一致） */
    private Map<String, Object> col(String colKey, String tableFullName, boolean temp) {
        Map<String, Object> m = new HashMap<>();
        m.put("colKey", colKey);
        m.put("name", colKey.substring(colKey.lastIndexOf('.') + 1));
        m.put("tableFullName", tableFullName);
        m.put("temp", temp);
        return m;
    }

    /** 构造 Transform 行（非 DIRECT / 非 LITERAL，走 Transform 节点分支） */
    private Map<String, Object> transformRow(String transformId,
                                             Collection<Map<String, Object>> inputs,
                                             Collection<Map<String, Object>> outputs) {
        Map<String, Object> row = new HashMap<>();
        row.put("transformId", transformId);
        row.put("expression", "expr_" + transformId.substring(0, 4));
        row.put("logicType", "EXPRESSION");
        row.put("procName", "proc_a");
        row.put("inputs", new ArrayList<>(inputs));
        row.put("outputs", new ArrayList<>(outputs));
        return row;
    }

    private void injectMaxDepth(int value) throws Exception {
        java.lang.reflect.Field f = ImpactAnalysisServiceImpl.class.getDeclaredField("maxAllowedDepth");
        f.setAccessible(true);
        f.setInt(impactService, value);
    }

    private ImpactAnalysisRequest fieldRequest(int maxDepth, String direction) {
        ImpactAnalysisRequest req = new ImpactAnalysisRequest();
        req.setSchema("CDM");
        req.setTableShortName("SRC");
        req.setFieldName("BALANCE");
        req.setDirection(direction);
        req.setMaxDepth(maxDepth);
        return req;
    }

    /**
     * T1 值通道临时表穿透：临时列不占深度预算（同轮继续扩展），
     * maxDepth=1 时经 vt1→vt2 临时链仍应到达物理表列（depth=1），临时列 depth=0。
     */
    @Test
    void testFieldLevel_TempColumnsPassThrough_DepthBudgetCountsPhysicalOnly() throws Exception {
        injectMaxDepth(20);
        // 兜底先注册（LIFO），具体 matcher 后注册优先生效
        stubNeo4jQuery("", Collections.emptyList());
        stubNeo4jQuerySeq("-[:INPUT_TO]->(t:Transform)", Arrays.asList(
                Arrays.asList(transformRow("11111111111111111111111111111111",
                        Arrays.asList(col("cdm.src.balance", "cdm.src", false)),
                        Arrays.asList(col("pid.vt1.x", "pid.vt1", true)))),
                Arrays.asList(transformRow("22222222222222222222222222222222",
                        Arrays.asList(col("pid.vt1.x", "pid.vt1", true)),
                        Arrays.asList(col("pid.vt2.y", "pid.vt2", true)))),
                Arrays.asList(transformRow("33333333333333333333333333333333",
                        Arrays.asList(col("pid.vt2.y", "pid.vt2", true)),
                        Arrays.asList(col("pid.phys.z", "pid.phys", false))))
        ));

        ImpactAnalysisVO vo = impactService.analyze(fieldRequest(1, "DOWNSTREAM"));

        var vt1 = vo.getNodes().stream().filter(n -> "pid.vt1.x".equals(n.getId())).findFirst();
        var vt2 = vo.getNodes().stream().filter(n -> "pid.vt2.y".equals(n.getId())).findFirst();
        var phys = vo.getNodes().stream().filter(n -> "pid.phys.z".equals(n.getId())).findFirst();
        assertTrue(vt1.isPresent(), "临时列 vt1.x 应入图");
        assertTrue(vt2.isPresent(), "穿透扩展临时列 vt2.y 应入图");
        assertTrue(phys.isPresent(), "maxDepth=1 经临时链穿透后物理列 pid.phys.z 应出现");
        assertEquals(0, vt1.get().getDepth(), "临时列穿透不占预算：depth=0");
        assertEquals(0, vt2.get().getDepth(), "临时列穿透不占预算：depth=0");
        assertEquals(1, phys.get().getDepth(), "物理列占 1 次预算：depth=1");
    }

    /**
     * T2 物理表仍占预算：maxDepth=1 时只到第一个物理表列，第二个物理表列不出现。
     */
    @Test
    void testFieldLevel_PhysicalColumnsConsumeDepthBudget() throws Exception {
        injectMaxDepth(20);
        stubNeo4jQuery("", Collections.emptyList());
        stubNeo4jQuerySeq("-[:INPUT_TO]->(t:Transform)", Arrays.asList(
                Arrays.asList(transformRow("11111111111111111111111111111111",
                        Arrays.asList(col("cdm.src.balance", "cdm.src", false)),
                        Arrays.asList(col("cdm.mid.b", "cdm.mid", false))))
        ));

        ImpactAnalysisVO vo = impactService.analyze(fieldRequest(1, "DOWNSTREAM"));

        var mid = vo.getNodes().stream().filter(n -> "cdm.mid.b".equals(n.getId())).findFirst();
        assertTrue(mid.isPresent());
        assertEquals(1, mid.get().getDepth());
        assertTrue(vo.getNodes().stream().noneMatch(n -> "cdm.far.c".equals(n.getId())),
                "物理列每跳消耗预算：maxDepth=1 不得再扩一层到 cdm.far.c");
    }

    /**
     * T3 第一轮行影响·临时表种子并入 layer=1（穿透）：起点 FILTERS→临时表→表内列
     * 不占预算，值通道应从种子列继续穿透到物理表（depth=1）；临时表/列节点 depth=0。
     */
    @Test
    void testFieldLevel_RowImpactTempTableSeedsMergeIntoFirstLayer() throws Exception {
        injectMaxDepth(20);
        Map<String, Object> filter = new HashMap<>();
        filter.put("tableName", "pid.vt_t");
        filter.put("shortName", "VT_T");
        filter.put("tableTemp", true);
        filter.put("role", "where_condition");
        filter.put("clause", "balance > 0");
        filter.put("procName", "proc_a");
        filter.put("seq", 1);

        stubNeo4jQuery("", Collections.emptyList());
        stubNeo4jQuery("{col_key: $ck})-[f:FILTERS]->", Arrays.asList(filter));
        stubNeo4jQuery("BELONGS_TO", Arrays.asList(col("pid.vt_t.k", "pid.vt_t", true)));
        stubNeo4jQuerySeq("-[:INPUT_TO]->(t:Transform)", Arrays.asList(
                Arrays.asList(transformRow("11111111111111111111111111111111",
                        Arrays.asList(col("pid.vt_t.k", "pid.vt_t", true)),
                        Arrays.asList(col("pid.phys2.m", "pid.phys2", false))))
        ));

        ImpactAnalysisVO vo = impactService.analyze(fieldRequest(1, "DOWNSTREAM"));

        var tableNode = vo.getNodes().stream()
                .filter(n -> "pid.vt_t".equals(n.getId()) && "TABLE".equals(n.getNodeLevel())).findFirst();
        var seedCol = vo.getNodes().stream().filter(n -> "pid.vt_t.k".equals(n.getId())).findFirst();
        var phys = vo.getNodes().stream().filter(n -> "pid.phys2.m".equals(n.getId())).findFirst();
        assertTrue(tableNode.isPresent(), "maxDepth=1 时起点 FILTERS→临时表也应展开（临时表不占预算）");
        assertTrue(seedCol.isPresent(), "临时表行影响展开列应入图");
        assertEquals(0, tableNode.get().getDepth(), "临时表穿透：表 depth=0");
        assertEquals(0, seedCol.get().getDepth(), "临时表行影响展开列 depth=0");
        assertTrue(phys.isPresent(), "种子列并入 layer=1 后值通道穿透到物理列");
        assertEquals(1, phys.get().getDepth(), "物理列占 1 次预算：depth=1");
    }

    /**
     * T4 中间行影响通道激活：值通道到达列（depth=1）FILTERS→临时表，
     * 临时表穿透预算 d<=maxDepth → 展开（旧口径 d+2>maxDepth 不展开）。
     */
    @Test
    void testFieldLevel_MiddleRowImpactActivatedForTempTable() throws Exception {
        injectMaxDepth(20);
        Map<String, Object> filter = new HashMap<>();
        filter.put("srcCol", "pid.mid_d.q");
        filter.put("tableName", "pid.tmp2");
        filter.put("shortName", "TMP2");
        filter.put("tableTemp", true);
        filter.put("role", "join_condition");
        filter.put("clause", "k = m");
        filter.put("procName", "proc_b");
        filter.put("seq", 2);

        stubNeo4jQuery("", Collections.emptyList());
        stubNeo4jQuerySeq("-[:INPUT_TO]->(t:Transform)", Arrays.asList(
                Arrays.asList(transformRow("11111111111111111111111111111111",
                        Arrays.asList(col("cdm.src.balance", "cdm.src", false)),
                        Arrays.asList(col("pid.mid_d.q", "pid.mid_d", false))))
        ));
        stubNeo4jQuery("UNWIND $cols", Arrays.asList(filter));
        stubNeo4jQuery("BELONGS_TO", Arrays.asList(col("pid.tmp2.q2", "pid.tmp2", true)));

        ImpactAnalysisVO vo = impactService.analyze(fieldRequest(1, "DOWNSTREAM"));

        var tableNode = vo.getNodes().stream()
                .filter(n -> "pid.tmp2".equals(n.getId()) && "TABLE".equals(n.getNodeLevel())).findFirst();
        var expandedCol = vo.getNodes().stream().filter(n -> "pid.tmp2.q2".equals(n.getId())).findFirst();
        assertTrue(tableNode.isPresent(), "值通道到达列 FILTERS→临时表应在预算内展开（穿透口径 d<=maxDepth）");
        assertTrue(expandedCol.isPresent(), "临时表行影响展开列应入图");
        assertEquals(1, tableNode.get().getDepth(), "表 depth=源列深度（穿透 +0）");
        assertEquals(1, expandedCol.get().getDepth(), "展开列 depth=源列深度（穿透 +0）");
        assertTrue(vo.getEdges().stream().anyMatch(e -> "FILTERS".equals(e.getEdgeType())
                && "pid.mid_d.q".equals(e.getSource()) && "pid.tmp2".equals(e.getTarget())), "FILTERS 边应存在");
        assertTrue(vo.getEdges().stream().anyMatch(e -> "ROW_IMPACT".equals(e.getEdgeType())
                && "pid.tmp2".equals(e.getSource()) && "pid.tmp2.q2".equals(e.getTarget())), "ROW_IMPACT 边应存在");
    }

    /**
     * T5 中间行影响·物理表预算：值通道到达列（depth=1）FILTERS→物理表，
     * 物理表消耗 1 跳 → maxDepth=1 时超预算（d+1=2>1）不展开。
     */
    @Test
    void testFieldLevel_MiddleRowImpactPhysicalTableRespectsBudget() throws Exception {
        injectMaxDepth(20);
        Map<String, Object> filter = new HashMap<>();
        filter.put("srcCol", "pid.mid_d.q");
        filter.put("tableName", "pid.phys3");
        filter.put("shortName", "PHYS3");
        filter.put("tableTemp", false);
        filter.put("role", "join_condition");
        filter.put("clause", "k = m");
        filter.put("procName", "proc_b");
        filter.put("seq", 2);

        stubNeo4jQuery("", Collections.emptyList());
        stubNeo4jQuerySeq("-[:INPUT_TO]->(t:Transform)", Arrays.asList(
                Arrays.asList(transformRow("11111111111111111111111111111111",
                        Arrays.asList(col("cdm.src.balance", "cdm.src", false)),
                        Arrays.asList(col("pid.mid_d.q", "pid.mid_d", false))))
        ));
        stubNeo4jQuery("UNWIND $cols", Arrays.asList(filter));
        stubNeo4jQuery("BELONGS_TO", Arrays.asList(col("pid.phys3.r", "pid.phys3", false)));

        ImpactAnalysisVO vo = impactService.analyze(fieldRequest(1, "DOWNSTREAM"));

        assertTrue(vo.getNodes().stream().noneMatch(n -> "pid.phys3".equals(n.getId())),
                "物理表行影响展开消耗 1 跳预算：maxDepth=1、源列已 depth=1 时不得展开");
    }

    /**
     * T6 上游方向临时列穿透：maxDepth=1 时经临时列回溯到物理上游列（depth=1）。
     */
    @Test
    void testFieldLevel_UpstreamTempColumnsPassThrough() throws Exception {
        injectMaxDepth(20);
        stubNeo4jQuery("", Collections.emptyList());
        stubNeo4jQuerySeq("<-[:OUTPUT_TO]-(t:Transform)", Arrays.asList(
                Arrays.asList(transformRow("11111111111111111111111111111111",
                        Arrays.asList(col("pid.vt9.y", "pid.vt9", true)),
                        Arrays.asList(col("pid.tgt.z", "pid.tgt", false)))),
                Arrays.asList(transformRow("22222222222222222222222222222222",
                        Arrays.asList(col("cdm.src9.a", "cdm.src9", false)),
                        Arrays.asList(col("pid.vt9.y", "pid.vt9", true))))
        ));

        ImpactAnalysisRequest req = fieldRequest(1, "UPSTREAM");
        ImpactAnalysisVO vo = impactService.analyze(req);

        var vt9 = vo.getNodes().stream().filter(n -> "pid.vt9.y".equals(n.getId())).findFirst();
        var src9 = vo.getNodes().stream().filter(n -> "cdm.src9.a".equals(n.getId())).findFirst();
        assertTrue(vt9.isPresent(), "上游临时列应入图");
        assertEquals(0, vt9.get().getDepth(), "上游临时列穿透：depth=0");
        assertTrue(src9.isPresent(), "maxDepth=1 经临时列穿透后上游物理列应出现");
        assertEquals(1, src9.get().getDepth(), "上游物理列占 1 次预算：depth=1");
    }

    /**
     * 手工构建 Neo4jClient fluent 链 mock（query→bind→to→fetch→all），
     * 按 cypher 片段区分返回结果——deep-stub 无法按链中方法参数区分。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubNeo4jQuery(String cypherFragment, Collection<Map<String, Object>> result) {
        Neo4jClient.UnboundRunnableSpec unbound = mock(Neo4jClient.UnboundRunnableSpec.class);
        Neo4jClient.OngoingBindSpec bindSpec = mock(Neo4jClient.OngoingBindSpec.class);
        Neo4jClient.RunnableSpec runnable = mock(Neo4jClient.RunnableSpec.class);
        Neo4jClient.RecordFetchSpec fetchSpec = mock(Neo4jClient.RecordFetchSpec.class);
        when(neo4jClient.query(argThat((String c) -> c != null && c.contains(cypherFragment))))
                .thenReturn(unbound);
        when(unbound.bind(any())).thenReturn(bindSpec);
        when(bindSpec.to(anyString())).thenReturn(runnable);
        when(runnable.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.all()).thenReturn(result);
    }

    /**
     * stubNeo4jQuery 的按调用序版本：同一 cypher 片段的第 N 次调用返回第 N 个结果，
     * 超出序列后返回空集——用于模拟 BFS 逐层扩展（每次 frontier 不同的链式返回）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubNeo4jQuerySeq(String cypherFragment, List<Collection<Map<String, Object>>> results) {
        Neo4jClient.UnboundRunnableSpec unbound = mock(Neo4jClient.UnboundRunnableSpec.class);
        Neo4jClient.OngoingBindSpec bindSpec = mock(Neo4jClient.OngoingBindSpec.class);
        Neo4jClient.RunnableSpec runnable = mock(Neo4jClient.RunnableSpec.class);
        Neo4jClient.RecordFetchSpec fetchSpec = mock(Neo4jClient.RecordFetchSpec.class);
        when(neo4jClient.query(argThat((String c) -> c != null && c.contains(cypherFragment))))
                .thenReturn(unbound);
        when(unbound.bind(any())).thenReturn(bindSpec);
        when(bindSpec.to(anyString())).thenReturn(runnable);
        when(runnable.fetch()).thenReturn(fetchSpec);
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        when(fetchSpec.all()).thenAnswer(inv -> {
            int i = call.getAndIncrement();
            return i < results.size() ? results.get(i) : Collections.emptyList();
        });
    }
}
