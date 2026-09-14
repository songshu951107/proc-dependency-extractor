package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Neo4j 全量同步任务进度实体（持久化，供页面常驻展示）
 */
@Data
@TableName("neo4j_sync_task")
public class Neo4jSyncTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务ID */
    private String taskId;

    /** RUNNING / DONE / FAILED */
    private String status;

    /** 任务总量 */
    private Integer total;

    /** 已完成数 */
    private Integer done;

    /** 成功数 */
    private Integer success;

    /** 失败数 */
    private Integer fail;

    /** 当前处理的存储过程 */
    private String currentProc;

    /** 任务级错误 */
    private String error;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}