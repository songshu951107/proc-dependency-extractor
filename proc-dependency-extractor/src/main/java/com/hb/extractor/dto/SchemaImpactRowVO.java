package com.hb.extractor.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量 schema 下游影响 - 每起点表一行的明细
 */
@Data
public class SchemaImpactRowVO {
    /** 起点表全名 schema.table */
    private String table;
    private Integer affectedTableCount;
    private Integer affectedProcCount;
    private Integer affectedReportCount;
    /** 单表失败原因 */
    private String error;
    private List<AffectedTableVO> affectedTables = new ArrayList<>();
    private List<String> affectedProcs = new ArrayList<>();
    private List<ReportImpactVO.ReportRef> affectedReports = new ArrayList<>();
}