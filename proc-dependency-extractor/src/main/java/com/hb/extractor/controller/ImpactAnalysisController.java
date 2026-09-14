package com.hb.extractor.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hb.extractor.dto.*;
import com.hb.extractor.entity.Neo4jSyncLog;
import com.hb.extractor.entity.Neo4jSyncTaskEntity;
import com.hb.extractor.mapper.Neo4jSyncLogMapper;
import com.hb.extractor.service.impl.AsyncNeo4jSyncService;
import com.hb.extractor.service.ImpactAnalysisService;
import com.hb.extractor.service.Neo4jSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/impact")
public class ImpactAnalysisController {

    @Autowired
    private ImpactAnalysisService impactAnalysisService;

    @Autowired
    private Neo4jSyncService neo4jSyncService;

    @Autowired
    private AsyncNeo4jSyncService asyncNeo4jSyncService;

    @Autowired
    private Neo4jSyncLogMapper neo4jSyncLogMapper;

    @GetMapping("/schemas")
    public Result<List<String>> getSchemas() {
        return Result.success(impactAnalysisService.getSchemas());
    }

    @GetMapping("/tables")
    public Result<List<TableOption>> getTables(@RequestParam String schema) {
        return Result.success(impactAnalysisService.getTables(schema));
    }

    @GetMapping("/fields")
    public Result<List<String>> getFields(@RequestParam String tableFullName) {
        return Result.success(impactAnalysisService.getFields(tableFullName));
    }

    @PostMapping("/analyze")
    public Result<ImpactAnalysisVO> analyze(@RequestBody ImpactAnalysisRequest request) {
        return Result.success(impactAnalysisService.analyze(request));
    }

    @PostMapping("/expand")
    public Result<ImpactAnalysisVO> expand(@RequestBody ExpandRequest request) {
        return Result.success(impactAnalysisService.expand(request));
    }

    @GetMapping("/paths")
    public Result<List<ImpactAnalysisVO.PathVO>> getPaths(
            @RequestParam String source,
            @RequestParam String target,
            @RequestParam(defaultValue = "10") Integer maxDepth) {
        return Result.success(impactAnalysisService.getPaths(source, target, maxDepth));
    }

    @PostMapping("/resync")
    public Result<SyncResult> resync(@RequestParam String procName) {
        return Result.success(neo4jSyncService.syncProc(procName));
    }

    @PostMapping("/resync/all")
    public Result<SyncResult> resyncAll() {
        return Result.success(neo4jSyncService.syncAll());
    }

    /**
     * 异步后台全量同步 Neo4j（立即返回 taskId，前端轮询进度）。
     */
    @PostMapping("/resync/all/async")
    public Result<String> resyncAllAsync() {
        return Result.success(asyncNeo4jSyncService.start());
    }

    /**
     * 查询异步全量同步任务进度。
     */
    @GetMapping("/resync/all/task/{taskId}")
    public Result<Neo4jSyncTaskEntity> resyncAllTask(@PathVariable String taskId) {
        Neo4jSyncTaskEntity task = asyncNeo4jSyncService.get(taskId);
        if (task == null) {
            return Result.error(404, "任务不存在或已清除");
        }
        return Result.success(task);
    }

    /**
     * 查询最近一次全量同步任务进度（页面常驻展示用）。
     */
    @GetMapping("/resync/all/task/latest")
    public Result<Neo4jSyncTaskEntity> resyncAllLatest() {
        return Result.success(asyncNeo4jSyncService.latest());
    }

    /**
     * 分页查询 Neo4j 同步日志（报错清单）。
     * GET /api/impact/sync-log?status=FAILED&pageNum=1&pageSize=10&since=...
     * status 为空时返回全部；since(格式 yyyy-MM-dd HH:mm:ss) 时仅返回该时间点之后更新的记录，
     * 用于"只看最近一次同步"隐藏历史失败。
     */
    @GetMapping("/sync-log")
    public Result<Page<Neo4jSyncLog>> pageSyncLog(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String since,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        LambdaQueryWrapper<Neo4jSyncLog> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(Neo4jSyncLog::getSyncStatus, status.trim().toUpperCase());
        }
        if (since != null && !since.isBlank()) {
            wrapper.ge(Neo4jSyncLog::getUpdateTime, since);
        }
        wrapper.orderByDesc(Neo4jSyncLog::getUpdateTime);
        return Result.success(neo4jSyncLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper));
    }

    @PostMapping("/clear-neo4j")
    public Result<Void> clearNeo4j() {
        neo4jSyncService.clearAllData();
        return Result.success(null);
    }

    @GetMapping("/sync-status")
    public Result<List<Neo4jSyncLogVO>> getSyncStatus() {
        return Result.success(impactAnalysisService.getSyncStatus());
    }
}
