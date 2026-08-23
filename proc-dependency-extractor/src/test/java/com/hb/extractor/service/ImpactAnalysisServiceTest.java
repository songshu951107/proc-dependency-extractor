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
}
