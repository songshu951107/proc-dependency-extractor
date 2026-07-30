package com.hb.extractor.service;

import com.hb.extractor.dto.SyncResult;
import com.hb.extractor.entity.*;
import com.hb.extractor.mapper.*;
import com.hb.extractor.service.impl.Neo4jSyncServiceImpl;
import org.junit.jupiter.api.BeforeEach;
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
class Neo4jSyncServiceTest {

    @Mock
    private Neo4jClient neo4jClient;
    @Mock
    private ProcInfoMapper procInfoMapper;
    @Mock
    private TableLineageMapper tableLineageMapper;
    @Mock
    private TableLineageFilterMapper tableLineageFilterMapper;
    @Mock
    private TableFieldMappingMapper tableFieldMappingMapper;
    @Mock
    private SqlBlockInfoMapper sqlBlockInfoMapper;
    @Mock
    private Neo4jSyncLogMapper neo4jSyncLogMapper;

    @InjectMocks
    private Neo4jSyncServiceImpl syncService;

    @Mock
    private Neo4jClient.UnboundRunnableSpec unboundRunnableSpec;

    @Mock
    private Neo4jClient.OngoingBindSpec ongoingBindSpec;

    @BeforeEach
    void setUp() {
        when(neo4jClient.query(anyString())).thenReturn(unboundRunnableSpec);
        when(unboundRunnableSpec.bindAll(anyMap())).thenReturn(unboundRunnableSpec);
        when(unboundRunnableSpec.bind(any())).thenReturn(ongoingBindSpec);
        when(ongoingBindSpec.to(anyString())).thenReturn(unboundRunnableSpec);
        when(unboundRunnableSpec.run()).thenReturn(null);
    }

    @Test
    void testSyncProc_ProcInfoNotExist_ReturnsFailure() {
        when(procInfoMapper.selectOne(any())).thenReturn(null);

        SyncResult result = syncService.syncProc("nonexistent_proc");

        assertFalse(result.isSuccess());
        assertEquals("nonexistent_proc", result.getProcName());
        verify(neo4jSyncLogMapper).insert(any(Neo4jSyncLog.class));
    }

    @Test
    void testSyncProc_Success() {
        ProcInfo procInfo = new ProcInfo();
        procInfo.setProcName("test_proc");
        procInfo.setUpdateTarget("CDM.TEST_TABLE");
        when(procInfoMapper.selectOne(any())).thenReturn(procInfo);
        when(tableLineageMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(sqlBlockInfoMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(neo4jSyncLogMapper.selectById(any())).thenReturn(null);

        SyncResult result = syncService.syncProc("test_proc");

        assertTrue(result.isSuccess());
        verify(neo4jSyncLogMapper).updateById(argThat((Neo4jSyncLog log) -> "SUCCESS".equals(log.getSyncStatus())));
    }

    @Test
    void testDeleteProcData_Neo4jUnavailable_NoException() {
        when(neo4jClient.query(anyString())).thenThrow(new RuntimeException("Neo4j 不可用"));

        assertDoesNotThrow(() -> syncService.deleteProcData("test_proc"));
    }
}
