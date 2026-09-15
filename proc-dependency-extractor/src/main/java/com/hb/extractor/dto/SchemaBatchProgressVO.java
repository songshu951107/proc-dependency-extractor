package com.hb.extractor.dto;

import lombok.Data;

/**
 * 批量 schema 下游影响 - 异步任务进度 VO
 */
@Data
public class SchemaBatchProgressVO {
    private String taskId;
    private String schema;
    /** running / done / failed */
    private String status;
    /** 起点表总数 */
    private int total;
    /** 已处理表数 */
    private int done;
    /** 失败表数 */
    private int failed;
    /** 完成百分比 0-100 */
    private int percent;
    /** 任务级失败原因 */
    private String error;
    private long createTime;
}