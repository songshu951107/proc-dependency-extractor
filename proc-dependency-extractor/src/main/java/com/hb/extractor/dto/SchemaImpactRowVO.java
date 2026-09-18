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
    /** 起点表自身被引用的报表数（反向查询起点表 ← Dataset ← Report） */
    private Integer selfReportCount;
    /** 单表失败原因 */
    private String error;
    private List<AffectedTableVO> affectedTables = new ArrayList<>();
    private List<String> affectedProcs = new ArrayList<>();
    private List<ReportImpactVO.ReportRef> affectedReports = new ArrayList<>();
    /** 起点表自身被引用的报表明细（按 file 去重） */
    private List<ReportImpactVO.ReportRef> selfReports = new ArrayList<>();
}