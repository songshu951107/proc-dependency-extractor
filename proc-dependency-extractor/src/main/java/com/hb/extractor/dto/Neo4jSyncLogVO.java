package com.hb.extractor.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Neo4j 同步日志 VO（前端展示用）
 */
@Data
public class Neo4jSyncLogVO {

    private String procName;
    private String syncStatus;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime lastSyncTime;
}
