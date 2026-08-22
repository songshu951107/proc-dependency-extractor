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
     * 行影响通道去重：同一目标表被多条 FILTERS 边指向（如多个 proc / 同 proc 多块过滤同一表）时，
     * 表内列只应展开一次——ROW_IMPACT 边数 = 表内列数，不随 FILTERS 边数翻倍；
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

        // 两条 FILTERS 边（不同 proc）指向同一目标表 cdm.tgt
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
        f2.put("role", "join_condition");
        f2.put("clause", "id = id");
        f2.put("procName", "proc_b");
        f2.put("seq", 2);

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
        stubNeo4jQuery(")-[f:FILTERS]->", Arrays.asList(f1, f2));
        stubNeo4jQuery("BELONGS_TO", Arrays.asList(colA, colB));

        ImpactAnalysisVO vo = impactService.analyze(req);

        long filtersEdges = vo.getEdges().stream()
                .filter(e -> "FILTERS".equals(e.getEdgeType())).count();
        long rowImpactEdges = vo.getEdges().stream()
                .filter(e -> "ROW_IMPACT".equals(e.getEdgeType())).count();
        assertEquals(2, filtersEdges, "两条 FILTERS 边（不同 proc）应各保留一条");
        assertEquals(2, rowImpactEdges, "同一表被过滤两次时 ROW_IMPACT 边不应翻倍（修复前为 4）");
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
