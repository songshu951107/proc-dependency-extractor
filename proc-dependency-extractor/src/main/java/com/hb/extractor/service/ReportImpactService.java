package com.hb.extractor.service;

import com.hb.extractor.dto.ReportImpactVO;

/**
 * 报表影响分析服务接口
 */
public interface ReportImpactService {

    /** 场景1：表级查询 */
    ReportImpactVO findReportsByTable(String schema, String table);

    /** 场景2：字段级查询 */
    ReportImpactVO findReportsByField(String schema, String table, String field);

    /** 场景3：口径查询 */
    ReportImpactVO traceFieldSource(String reportFile, String datasetName, String columnName);

    /** 场景5：跨链影响 */
    ReportImpactVO analyzeCrossImpact(String schema, String table, String field);
}
