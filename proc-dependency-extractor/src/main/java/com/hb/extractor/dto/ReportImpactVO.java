package com.hb.extractor.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 报表影响分析响应 VO
 */
@Data
public class ReportImpactVO {

    /** 查询的表名（小写 schema.table） */
    private String table;
    /** 查询的字段名（大写） */
    private String field;
    /** 受影响的报表列表 */
    private List<ReportRef> reports = new ArrayList<>();
    /** 字段口径来源（场景3） */
    private List<FieldSource> fieldSources = new ArrayList<>();
    /** 字段映射绑定明细（口径按列头查询时返回） */
    private List<BindingDetail> bindings = new ArrayList<>();

    @Data
    public static class ReportRef {
        private String file;
        private String name;
        private String datasetName;
        private String connection;
        private String selectAlias;
        private String sourceExpr;
        private List<String> cellRefs = new ArrayList<>();
    }

    @Data
    public static class FieldSource {
        private String sourceTable;
        private String sourceField;
        private String sourceExpr;
        private String connection;
        private String datasetName;
    }

    @Data
    public static class BindingDetail {
        private String reportFile;
        private String datasetName;
        private String cellPos;
        private String columnName;
        private String headerText;
        private String selectAlias;
        private String sourceTable;
        private String sourceField;
        private String sourceExpr;
    }
}
