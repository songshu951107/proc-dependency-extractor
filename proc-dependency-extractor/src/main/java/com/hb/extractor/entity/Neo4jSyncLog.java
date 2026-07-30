package com.hb.extractor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Neo4j 同步日志实体
 */
@Data
@TableName("neo4j_sync_log")
public class Neo4jSyncLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 存储过程名 */
    private String procName;

    /** 同步状态: PENDING / SUCCESS / FAILED */
    private String syncStatus;

    /** 错误信息 */
    private String errorMessage;

    /** 重试次数 */
    private Integer retryCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
