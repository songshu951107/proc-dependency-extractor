package com.hb.extractor.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.entity.Neo4jSyncTaskEntity;
import com.hb.extractor.entity.ProcInfo;
import com.hb.extractor.entity.RebuildTaskEntity;
import com.hb.extractor.mapper.Neo4jSyncTaskMapper;
import com.hb.extractor.mapper.ProcInfoMapper;
import com.hb.extractor.mapper.RebuildTaskMapper;
import com.hb.extractor.service.Neo4jSyncService;
import com.hb.extractor.service.ProcExtractService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 全量重新解析异步任务服务。
 * 任务进度持久化到 rebuild_task 表，页面可常驻展示（刷新不丢失）。
 */
@Slf4j
@Service
public class AsyncExtractService {

    @Autowired
    private ProcExtractService procExtractService;

    @Autowired
    private ProcInfoMapper procInfoMapper;

    @Autowired
    private Neo4jSyncService neo4jSyncService;

    @Autowired
    private RebuildTaskMapper rebuildTaskMapper;

    @Autowired
    private Neo4jSyncTaskMapper neo4jSyncTaskMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 失败明细收集上限，避免返回体过大 */
    private static final int MAX_FAILURES = 50;

    /**
     * 启动全量重新解析任务，持久化到 DB 后立即返回 taskId。
     */
    public String start() {
        RebuildTaskEntity task = new RebuildTaskEntity();
        task.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        task.setStatus("RUNNING");
        task.setTotal(0);
        task.setDone(0);
        task.setSuccess(0);
        task.setFail(0);
        task.setCreateTime(LocalDateTime.now());
        task.setStartTime(LocalDateTime.now());
        rebuildTaskMapper.insert(task);

        // 影子同步进度：随重建"顺便"推进 neo4j_sync_task，使两根进度条同频
        Neo4jSyncTaskEntity shadow = new Neo4jSyncTaskEntity();
        shadow.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        shadow.setStatus("RUNNING");
        shadow.setTotal(0);
        shadow.setDone(0);
        shadow.setSuccess(0);
        shadow.setFail(0);
        shadow.setCreateTime(LocalDateTime.now());
        shadow.setStartTime(LocalDateTime.now());
        neo4jSyncTaskMapper.insert(shadow);

        CompletableFuture.runAsync(() -> run(task, shadow));
        return task.getTaskId();
    }

    public RebuildTaskEntity get(String taskId) {
        return rebuildTaskMapper.selectOne(new LambdaQueryWrapper<RebuildTaskEntity>()
                .eq(RebuildTaskEntity::getTaskId, taskId));
    }

    /**
     * 最近一次任务（按创建时间倒序取最新一条）
     */
    public RebuildTaskEntity latest() {
        LambdaQueryWrapper<RebuildTaskEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(RebuildTaskEntity::getId).last("limit 1");
        return rebuildTaskMapper.selectOne(wrapper);
    }

    private void run(RebuildTaskEntity task, Neo4jSyncTaskEntity shadow) {
        final List<String> failures = new ArrayList<>();
        try {
            List<ProcInfo> procs = procInfoMapper.selectList(null);
            task.setTotal(procs.size());
            shadow.setTotal(procs.size());
            for (ProcInfo proc : procs) {
                task.setCurrentProc(proc.getProcName());
                shadow.setCurrentProc(proc.getProcName());
                try {
                    procExtractService.reparse(proc.getProcName());
                    task.setSuccess(task.getSuccess() + 1);
                    try {
                        neo4jSyncService.syncProc(proc.getProcName());
                        shadow.setSuccess(shadow.getSuccess() + 1);
                    } catch (Exception e) {
                        shadow.setFail(shadow.getFail() + 1);
                        log.warn("全量重解析 Neo4j 同步失败 proc={}: {}", proc.getProcName(), e.getMessage());
                    }
                } catch (Exception e) {
                    task.setFail(task.getFail() + 1);
                    shadow.setFail(shadow.getFail() + 1);
                    if (failures.size() < MAX_FAILURES) {
                        failures.add(proc.getProcName() + ": " + e.getMessage());
                    }
                    log.error("全量重解析失败 proc={}", proc.getProcName(), e);
                }
                task.setDone(task.getDone() + 1);
                shadow.setDone(shadow.getDone() + 1);
                write(task, failures);
                neo4jSyncTaskMapper.updateById(shadow);
            }
            task.setStatus("DONE");
            shadow.setStatus("DONE");
        } catch (Exception e) {
            log.error("全量重新解析任务异常", e);
            task.setStatus("FAILED");
            task.setError(e.getMessage());
            shadow.setStatus("FAILED");
            shadow.setError(e.getMessage());
        } finally {
            task.setEndTime(LocalDateTime.now());
            write(task, failures);
            shadow.setEndTime(LocalDateTime.now());
            neo4jSyncTaskMapper.updateById(shadow);
        }
    }

    private void write(RebuildTaskEntity task, List<String> failures) {
        try {
            task.setFailures(failures.isEmpty() ? null : objectMapper.writeValueAsString(failures));
        } catch (JsonProcessingException e) {
            task.setFailures(null);
        }
        rebuildTaskMapper.updateById(task);
    }
}