package com.hb.extractor.parser.dto;

import lombok.Data;

/**
 * SQL 块解析失败明细（供解析失败清单落库/展示）
 */
@Data
public class ParseFailure {

    /** SQL 块序号 */
    private Integer blockSeq;

    /** 块类型（CREATE_TEMP / INSERT_SELECT / OTHER） */
    private String blockType;

    /** 失败原因 */
    private String failReason;

    /** SQL 预览（截断） */
    private String sqlPreview;
}