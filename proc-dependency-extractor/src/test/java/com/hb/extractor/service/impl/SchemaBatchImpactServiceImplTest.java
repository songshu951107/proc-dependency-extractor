package com.hb.extractor.service.impl;

import com.hb.extractor.dto.ReportImpactVO;
import com.hb.extractor.dto.SchemaImpactRowVO;
import com.hb.extractor.entity.TableLineage;
import com.hb.extractor.mapper.TableDdlInfoMapper;
import com.hb.extractor.mapper.TableLineageMapper;
import com.hb.extractor.service.ReportImpactService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchemaBatchImpactServiceImplTest {

    @Mock private TableDdlInfoMapper ddlInfoMapper;
    @Mock private TableLineageMapper tableLineageMapper;
    @Mock private ReportImpactService reportImpactService;
    @InjectMocks private SchemaBatchImpactServiceImpl service;

    private TableLineage lineage(String target, String proc) {
        TableLineage tl = new TableLineage();
        tl.setTargetTable(target);
        tl.setProcName(proc);
        tl.setTargetIsTemp(0);
        return tl;
    }

    @Test
    void buildRow_dedupeTables_procs_andReports() {
        when(tableLineageMapper.selectList(any())).thenReturn(Arrays.asList(
                lineage("cdm.c_agt_ldr_acct_intst", "p1"),
                lineage("cdm.c_agt_ldr_acct_intst", "p2"),
                lineage("pid.a_d_fin_mkt_prft_loss_dtl", "p1")));

        ReportImpactVO.ReportRef r1 = new ReportImpactVO.ReportRef();
        r1.setFile("rpt_a.xlsx"); r1.setName("A");
        ReportImpactVO.ReportRef r2 = new ReportImpactVO.ReportRef();
        r2.setFile("rpt_a.xlsx"); r2.setName("A");
        ReportImpactVO.ReportRef r3 = new ReportImpactVO.ReportRef();
        r3.setFile("rpt_b.xlsx"); r3.setName("B");
        ReportImpactVO voCdm = new ReportImpactVO();
        voCdm.getReports().add(r1);
        ReportImpactVO voPid = new ReportImpactVO();
        voPid.getReports().add(r2);
        voPid.getReports().add(r3);
        when(reportImpactService.findReportsByTable("cdm", "c_agt_ldr_acct_intst")).thenReturn(voCdm);
        when(reportImpactService.findReportsByTable("pid", "a_d_fin_mkt_prft_loss_dtl")).thenReturn(voPid);

        SchemaImpactRowVO row = service.buildRow("fdm.f_ac_ctds_ldr_acct");

        // 两张物理表去重；两个 proc 去重；rpt_a.xlsx 跨表去重只算一次
        assertEquals(2, row.getAffectedTableCount());
        assertEquals(2, row.getAffectedProcs().size());
        assertEquals(2, row.getAffectedReportCount());
        assertEquals(2, row.getAffectedTables().size());
        assertEquals(2, row.getAffectedReports().size());
    }

    @Test
    void sweep_removesExpiredNonLatest_keepsLatest() {
        var a = service.registerTaskForTest("FD_M");
        service.markTaskDone(a);
        service.ageTask(a, 31L * 60 * 1000); // 31 分钟，超过 30 分钟 TTL
        var b = service.registerTaskForTest("fd_m"); // 同一 schema 的最新任务

        service.sweep();

        assertEquals(1, service.taskCount());
        assertNotNull(service.getProgress(b.getTaskId()));
    }
}