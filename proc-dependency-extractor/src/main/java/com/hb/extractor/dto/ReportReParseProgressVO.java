package com.hb.extractor.dto;

import lombok.Data;

import java.util.Date;

/**
 * 报表全量重解析（异步）任务进度
 */
@Data
public class ReportReParseProgressVO {
    private String taskId;
    /** RUNNING / DONE / FAILED */
    private String status;
    private Integer total;
    private Integer done;
    private Integer success;
    private Integer fail;
    /** 当前正在处理的报表文件 */
    private String current;
    private String error;
    /** 失败报表明细（文件名: 原因），前端用于展示具体哪些报表解析失败 */
    private java.util.List<String> failList;
    private Date createTime;
    private Integer percent;
}