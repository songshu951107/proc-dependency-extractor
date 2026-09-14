package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hb.extractor.entity.Neo4jSyncTaskEntity;
import com.hb.extractor.entity.ProcInfo;
import com.hb.extractor.mapper.Neo4jSyncTaskMapper;
import com.hb.extractor.mapper.ProcInfoMapper;
import com.hb.extractor.service.Neo4jSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Neo4j 全量同步异步任务服务。
 * 任务进度持久化到 neo4j_sync_task 表，页面可常驻展示（刷新不丢失）。
 * 与全量重解析的 AsyncExtractService 各自独立，互不影响。
 */
@Slf4j
@Service
public class AsyncNeo4jSyncService {

    @Autowired
    private Neo4jSyncService neo4jSyncService;

    @Autowired
    private ProcInfoMapper procInfoMapper;

    @Autowired
    private Neo4jSyncTaskMapper neo4jSyncTaskMapper;

    /**
     * 启动 Neo4j 全量同步任务，持久化到 DB 后立即返回 taskId。
     */
    public String start() {
        Neo4jSyncTaskEntity task = new Neo4jSyncTaskEntity();
        task.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        task.setStatus("RUNNING");
        task.setTotal(0);
        task.setDone(0);
        task.setSuccess(0);
        task.setFail(0);
        task.setCreateTime(LocalDateTime.now());
        task.setStartTime(LocalDateTime.now());
        neo4jSyncTaskMapper.insert(task);
        CompletableFuture.runAsync(() -> run(task));
        return task.getTaskId();
    }

    public Neo4jSyncTaskEntity get(String taskId) {
        return neo4jSyncTaskMapper.selectOne(new LambdaQueryWrapper<Neo4jSyncTaskEntity>()
                .eq(Neo4jSyncTaskEntity::getTaskId, taskId));
    }

    /**
     * 最近一次任务（按创建时间倒序取最新一条）
     */
    public Neo4jSyncTaskEntity latest() {
        LambdaQueryWrapper<Neo4jSyncTaskEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Neo4jSyncTaskEntity::getId).last("limit 1");
        return neo4jSyncTaskMapper.selectOne(wrapper);
    }

    private void run(Neo4jSyncTaskEntity task) {
        try {
            List<ProcInfo> procs = procInfoMapper.selectList(null);
            task.setTotal(procs.size());
            for (ProcInfo proc : procs) {
                task.setCurrentProc(proc.getProcName());
                try {
                    neo4jSyncService.syncProc(proc.getProcName());
                    task.setSuccess(task.getSuccess() + 1);
                } catch (Exception e) {
                    task.setFail(task.getFail() + 1);
                    log.error("Neo4j 同步失败 proc={}", proc.getProcName(), e);
                }
                task.setDone(task.getDone() + 1);
                neo4jSyncTaskMapper.updateById(task);
            }
            task.setStatus("DONE");
        } catch (Exception e) {
            log.error("Neo4j 全量同步任务异常", e);
            task.setStatus("FAILED");
            task.setError(e.getMessage());
        } finally {
            task.setEndTime(LocalDateTime.now());
            neo4jSyncTaskMapper.updateById(task);
        }
    }
}