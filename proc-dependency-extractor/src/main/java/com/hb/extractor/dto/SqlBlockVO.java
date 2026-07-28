package com.hb.extractor.dto;

import lombok.Data;

import java.util.List;

/**
 * SQL 块详情 VO
 */
@Data
public class SqlBlockVO {
    private Integer blockSeq;
    private String blockType;
    private String targetTable;
    private String targetTableType;
    private String mainTable;
    private String mainTableType;
    private Integer startLine;
    private Integer endLine;
    private String sqlContent;
    private List<JoinTable> joinTables;
    private String whereConditions;
    /** 兼容前端 FilterDetail 结构 */
    private List<LineageGraphVO.FilterDetail> filters;

    @Data
    public static class JoinTable {
        private String tableName;
        private String tableType;
        private String alias;
        private String joinType;
        private String conditions;
    }
}
