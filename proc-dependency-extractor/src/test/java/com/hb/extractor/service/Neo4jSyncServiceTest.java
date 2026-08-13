package com.hb.extractor.service;

import com.hb.extractor.dto.SyncResult;
import com.hb.extractor.entity.*;
import com.hb.extractor.mapper.*;
import com.hb.extractor.parser.DdlParser;
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
    private TableTransformInputMapper tableTransformInputMapper;
    @Mock
    private SqlBlockInfoMapper sqlBlockInfoMapper;
    @Mock
    private Neo4jSyncLogMapper neo4jSyncLogMapper;
    @Mock
    private DdlParser ddlParser;

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

    /**
     * 验证 syncProc 在有字段映射 + Transform 输入时，会创建 Transform/INPUT_TO/OUTPUT_TO
     * （而非旧的 MAPS_TO）。重点验证不抛异常且以 SUCCESS 收尾。
     */
    @Test
    void testSyncProc_TransformNodeSync_Success() {
        ProcInfo procInfo = new ProcInfo();
        procInfo.setProcName("test_proc");
        procInfo.setUpdateTarget("CDM.TEST_TABLE");

        TableLineage edge = new TableLineage();
        edge.setId(1L);
        edge.setProcName("test_proc");
        edge.setSourceTable("FDM.SRC_TABLE");
        edge.setSourceTableShort("SRC_TABLE");
        edge.setTargetTable("CDM.TEST_TABLE");
        edge.setTargetTableShort("TEST_TABLE");
        edge.setTargetIsTemp(0);
        edge.setOperation("INSERT_SELECT");
        edge.setSqlBlockSeq(1);

        TableFieldMapping fm = new TableFieldMapping();
        fm.setId(10L);
        fm.setLineageId(1L);
        fm.setProcName("test_proc");
        fm.setTargetField("acct_no");
        fm.setFieldOrder(1);
        fm.setSourceExpr("T01.acct_no");
        fm.setSourceAlias("T01");
        fm.setSourceTableShort("SRC_TABLE");
        fm.setLogicType("DIRECT");

        TableTransformInput input = new TableTransformInput();
        input.setFieldMappingId(10L);
        input.setSourceTableShort("SRC_TABLE");
        input.setSourceField("acct_no");
        input.setSourceAlias("T01");
        input.setRole("value");
        input.setInputOrder(1);

        when(procInfoMapper.selectOne(any())).thenReturn(procInfo);
        when(tableLineageMapper.selectList(any())).thenReturn(Collections.singletonList(edge));
        when(tableFieldMappingMapper.selectList(any())).thenReturn(Collections.singletonList(fm));
        when(tableTransformInputMapper.selectList(any())).thenReturn(Collections.singletonList(input));
        when(neo4jSyncLogMapper.selectById(any())).thenReturn(null);
        when(ddlParser.parse(anyString())).thenReturn(Collections.emptyList());

        SyncResult result = syncService.syncProc("test_proc");

        assertTrue(result.isSuccess());
        // 应执行多次 Cypher 写入（Transform 节点 + INPUT_TO + OUTPUT_TO 等）
        verify(neo4jClient, atLeast(5)).query(anyString());
        verify(neo4jSyncLogMapper).updateById(argThat((Neo4jSyncLog log) -> "SUCCESS".equals(log.getSyncStatus())));
    }
}
