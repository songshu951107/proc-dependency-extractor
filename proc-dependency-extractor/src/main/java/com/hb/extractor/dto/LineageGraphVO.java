package com.hb.extractor.dto;

import lombok.Data;

import java.util.List;

/**
 * 血缘图响应对象
 */
@Data
public class LineageGraphVO {
    private String procName;
    private String updateTarget;
    private List<Node> nodes;
    private List<Edge> edges;
    /** 递归是否被 depth 截断 */
    private Boolean truncated;

    @Data
    public static class Node {
        /** 表短名 */
        private String id;
        /** 完整名 */
        private String fullTableName;
        /** SOURCE / TEMP / TARGET */
        private String nodeType;
        /** schema 变量名 */
        private String schemaName;
    }

    @Data
    public static class Edge {
        private String source;
        private String target;
        private String operation;
        private Integer sqlBlockSeq;
        private List<FilterDetail> filters;
        /** 主表短名 */
        private String mainTable;
        /** 字段映射概要 */
        private List<FieldMappingBrief> fieldMappings;
    }

    @Data
    public static class FilterDetail {
        private String filterType;
        private String filterClause;
        private List<String> fields;
    }

    @Data
    public static class FieldMappingBrief {
        private String targetField;
        private String sourceExpr;
        private String sourceTableShort;
    }
}
