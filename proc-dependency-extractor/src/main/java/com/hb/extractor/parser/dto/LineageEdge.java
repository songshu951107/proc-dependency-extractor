package com.hb.extractor.parser.dto;

import lombok.Data;

/**
 * 血缘派生边（source → target）
 */
@Data
public class LineageEdge {
    /** 派生源表完整名 */
    private String sourceTable;
    /** 源表短名(去schema) */
    private String sourceTableShort;
    /** 派生目标表完整名 */
    private String targetTable;
    /** 目标表短名 */
    private String targetTableShort;
    /** 目标是否临时表 */
    private boolean targetIsTemp;
    /** 派生操作:CREATE TEMP/INSERT SELECT/MERGE/UPDATE FROM/DELETE FROM */
    private String operation;
    /** SQL块序号(从1开始) */
    private int sqlBlockSeq;
    /** FROM 后的主表短名 */
    private String mainTable;
    /** 本块 ON/WHERE 条件字段（行血缘；按 sourceTableShort 归属到本边，未归属挂块内第一条边） */
    private java.util.List<ConditionField> conditionFields = new java.util.ArrayList<>();
}
