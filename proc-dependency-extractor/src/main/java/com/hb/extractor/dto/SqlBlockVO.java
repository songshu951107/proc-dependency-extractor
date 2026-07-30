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

    /**
     * 建表语句（CREATE TEMPORARY TABLE 的 sqlContent）
     * 当同一 targetTable 同时存在 CREATE_TEMP 和 INSERT_SELECT 块时，
     * 合并后把 CREATE_TEMP 块的 sqlContent 保留到这里，避免字段定义丢失。
     * 仅 INSERT_SELECT 块时为 null。
     */
    private String createDdl;
    /** 建表语句对应的 SQL 块序号（用于脚本查看器高亮跳转） */
    private Integer createBlockSeq;
    /** 建表语句起始行号（用于脚本跳转） */
    private Integer createStartLine;
    /** 建表语句结束行号 */
    private Integer createEndLine;

    @Data
    public static class JoinTable {
        private String tableName;
        private String tableType;
        private String alias;
        private String joinType;
        private String conditions;
    }
}
