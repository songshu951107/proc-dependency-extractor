package com.hb.extractor.dto;

import lombok.Data;

import java.util.List;

/**
 * 字段映射响应 VO（含递归追溯）
 */
@Data
public class FieldMappingVO {
    private String targetTable;
    private String targetTableType;
    private List<FieldItem> fields;
    private List<String> unmappedFields;

    @Data
    public static class FieldItem {
        private String targetField;
        private Integer fieldOrder;
        private List<DirectMapping> directMappings;
        /** 递归追溯的最终来源（recursive=true 时填充） */
        private UltimateSource ultimateSource;
    }

    @Data
    public static class DirectMapping {
        private String sourceTable;
        private String sourceTableType;
        private String sourceExpr;
        private String sourceAlias;
    }

    @Data
    public static class UltimateSource {
        private String sourceTable;
        private String sourceTableType;
        private String sourceField;
        private List<PathNode> path;
    }

    @Data
    public static class PathNode {
        private String table;
        private String field;
    }
}
