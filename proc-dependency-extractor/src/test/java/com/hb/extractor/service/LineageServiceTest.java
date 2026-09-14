package com.hb.extractor.service;

import com.hb.extractor.dto.LineageGraphVO;
import com.hb.extractor.entity.ProcInfo;
import com.hb.extractor.entity.TableLineage;
import com.hb.extractor.mapper.ProcInfoMapper;
import com.hb.extractor.mapper.TableLineageMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class LineageServiceTest {

    @Autowired
    private LineageService lineageService;
    @Autowired
    private ProcInfoMapper procInfoMapper;
    @Autowired
    private TableLineageMapper tableLineageMapper;

    private Long procId;
    private String procName = "test_lineage_service_proc";

    @AfterEach
    void cleanup() {
        tableLineageMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TableLineage>()
                .eq(TableLineage::getProcName, procName));
        if (procId != null) {
            procInfoMapper.deleteById(procId);
            procId = null;
        }
    }

    private ProcInfo createTestProc() {
        ProcInfo procInfo = new ProcInfo();
        procInfo.setProcName(procName);
        procInfo.setProcFileName(procName + ".py");
        procInfo.setFilePath("/tmp/" + procName + ".py");
        procInfo.setUpdateTarget("CDM.TARGET_TABLE");
        procInfo.setTableCount(1);
        procInfo.setTempTableCount(1);
        procInfo.setExtractTime(LocalDateTime.now());
        procInfoMapper.insert(procInfo);
        procId = procInfo.getId();
        return procInfo;
    }

    private void createTestEdge(String sourceShort, String sourceFull,
                                  String targetShort, String targetFull,
                                  int isTemp, int seq, String operation) {
        TableLineage edge = new TableLineage();
        edge.setProcId(procId);
        edge.setProcName(procName);
        edge.setSourceTable(sourceFull);
        edge.setSourceTableShort(sourceShort);
        edge.setTargetTable(targetFull);
        edge.setTargetTableShort(targetShort);
        edge.setTargetIsTemp(isTemp);
        edge.setOperation(operation);
        edge.setSqlBlockSeq(seq);
        edge.setExtractTime(LocalDateTime.now());
        tableLineageMapper.insert(edge);
    }

    @Test
    void testGetLineageGraph() {
        createTestProc();
        createTestEdge("F_A", "FDM.F_A", "VT_B", "CDM.VT_B", 1, 1, "CREATE TEMP");
        createTestEdge("VT_B", "CDM.VT_B", "TARGET_TABLE", "CDM.TARGET_TABLE", 0, 2, "INSERT SELECT");

        LineageGraphVO vo = lineageService.getLineageGraph(procName);

        assertNotNull(vo);
        assertEquals(procName, vo.getProcName());
        assertEquals("CDM.TARGET_TABLE", vo.getUpdateTarget());
        assertFalse(vo.getNodes().isEmpty());
        assertFalse(vo.getEdges().isEmpty());

        boolean hasSource = vo.getNodes().stream()
                .anyMatch(n -> "SOURCE".equals(n.getNodeType()) && "F_A".equals(n.getId()));
        boolean hasTarget = vo.getNodes().stream()
                .anyMatch(n -> "TARGET".equals(n.getNodeType()) && "TARGET_TABLE".equals(n.getId()));
        boolean hasTemp = vo.getNodes().stream()
                .anyMatch(n -> "TEMP".equals(n.getNodeType()) && "VT_B".equals(n.getId()));
        assertTrue(hasSource, "应包含 SOURCE 节点 F_A");
        assertTrue(hasTarget, "应包含 TARGET 节点 TARGET_TABLE");
        assertTrue(hasTemp, "应包含 TEMP 节点 VT_B");
    }

    @Test
    void testGetLineageGraphNotFound() {
        LineageGraphVO vo = lineageService.getLineageGraph("non_existent_proc_xyz");
        assertNull(vo);
    }

    @Test
    void testRecursiveUpstream() {
        createTestProc();
        // 构造 A → VT_B → TARGET 链路
        createTestEdge("F_A", "FDM.F_A", "VT_B", "CDM.VT_B", 1, 1, "CREATE TEMP");
        createTestEdge("VT_B", "CDM.VT_B", "TARGET_TABLE", "CDM.TARGET_TABLE", 0, 2, "INSERT SELECT");

        LineageGraphVO vo = lineageService.traceUpstream("CDM.TARGET_TABLE", 5);

        assertNotNull(vo);
        assertFalse(vo.getNodes().isEmpty());
        boolean hasA = vo.getNodes().stream().anyMatch(n -> "F_A".equals(n.getId()));
        boolean hasVTB = vo.getNodes().stream().anyMatch(n -> "VT_B".equals(n.getId()));
        assertTrue(hasA, "应包含上游表 F_A");
        assertTrue(hasVTB, "应包含中间临时表 VT_B");
    }

    @Test
    void testRecursiveDownstream() {
        createTestProc();
        createTestEdge("F_A", "FDM.F_A", "VT_B", "CDM.VT_B", 1, 1, "CREATE TEMP");
        createTestEdge("VT_B", "CDM.VT_B", "TARGET_TABLE", "CDM.TARGET_TABLE", 0, 2, "INSERT SELECT");

        LineageGraphVO vo = lineageService.traceDownstream("FDM.F_A", 5);

        assertNotNull(vo);
        assertFalse(vo.getNodes().isEmpty());
        boolean hasTarget = vo.getNodes().stream().anyMatch(n -> "TARGET_TABLE".equals(n.getId()));
        assertTrue(hasTarget, "应包含下游表 TARGET_TABLE");
    }
}
