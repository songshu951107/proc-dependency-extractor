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
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
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
        // 起点节点应存在
        assertFalse(vo.getNodes().isEmpty());
        assertEquals("CDM.TEST_TABLE", vo.getNodes().get(0).getId());
    }

    @Test
    void testGetSyncStatus_ReturnsFailedLogs() {
        when(neo4jSyncLogMapper.selectList(any())).thenReturn(Collections.emptyList());

        var result = impactService.getSyncStatus();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
