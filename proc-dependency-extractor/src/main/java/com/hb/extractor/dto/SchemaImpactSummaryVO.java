package com.hb.extractor.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量 schema 下游影响 - 汇总矩阵结果
 */
@Data
public class SchemaImpactSummaryVO {
    private String schema;
    /** 起点表总数 */
    private int totalTables;
    /** 失败起点表数 */
    private int failedCount;
    private List<SchemaImpactRowVO> rows = new ArrayList<>();
    /** 全局受影响报表（按 file 去重，供导出 Sheet4） */
    private List<ReportImpactVO.ReportRef> affectedReports = new ArrayList<>();
}