package com.hb.extractor.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hb.extractor.entity.Neo4jSyncLog;
import com.hb.extractor.mapper.Neo4jSyncLogMapper;
import com.hb.extractor.service.Neo4jSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Neo4j 同步失败自动重试定时任务
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "scheduler.neo4j-retry.enabled", havingValue = "true", matchIfMissing = false)
public class Neo4jSyncRetryScheduler {

    @Autowired
    private Neo4jSyncService neo4jSyncService;

    @Autowired
    private Neo4jSyncLogMapper neo4jSyncLogMapper;

    @Value("${impact.sync.max-retry-count:3}")
    private int maxRetryCount;

    @Scheduled(fixedDelayString = "${impact.sync.retry-interval:300000}")
    public void retryFailedSyncs() {
        List<Neo4jSyncLog> failedLogs = neo4jSyncLogMapper.selectList(
                new LambdaQueryWrapper<Neo4jSyncLog>()
                        .eq(Neo4jSyncLog::getSyncStatus, "FAILED")
                        .lt(Neo4jSyncLog::getRetryCount, maxRetryCount));

        if (failedLogs.isEmpty()) return;

        log.info("Neo4j 同步重试: 发现 {} 个失败 proc", failedLogs.size());
        for (Neo4jSyncLog logEntry : failedLogs) {
            try {
                neo4jSyncService.syncProc(logEntry.getProcName());
            } catch (Exception e) {
                log.warn("重试同步失败 proc={}: {}", logEntry.getProcName(), e.getMessage());
            }
        }
    }
}
