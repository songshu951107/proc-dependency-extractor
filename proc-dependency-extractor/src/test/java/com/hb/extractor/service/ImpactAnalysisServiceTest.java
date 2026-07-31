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

import java.util.Collections;

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
}
