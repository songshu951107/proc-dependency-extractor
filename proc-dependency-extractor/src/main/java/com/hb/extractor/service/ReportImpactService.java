package com.hb.extractor.service;

import com.hb.extractor.dto.ImpactAnalysisVO;
import com.hb.extractor.dto.ReportImpactVO;

import java.util.List;

/**
 * 报表影响分析服务接口
 */
public interface ReportImpactService {

    /** 场景1：表级查询 */
    ReportImpactVO findReportsByTable(String schema, String table);

    /** 场景2：字段级查询 */
    ReportImpactVO findReportsByField(String schema, String table, String field);

    /** 场景3：口径查询（按报表表头汉字反查相关报表及字段映射） */
    ReportImpactVO findReportsByHeaderText(String headerText);

    /** 获取报表影响范围内的字段列表（基于 QUERIES 关系） */
    List<String> getFields(String schema, String table);

    /** 场景4：报表列溯源 - 获取指定报表的全部表头列汉字 */
    List<String> getReportHeaders(String reportFile);

    /**
     * 场景4：报表列溯源 - 根据报表+表头列追溯上游字段级链路
     * 内部根据 report_cell_binding 拿 source_table + source_field，
     * 再调用 ImpactAnalysisService.analyze(UPSTREAM, field)
     */
    ImpactAnalysisVO traceReportLineage(String reportFile, String headerText);
}
