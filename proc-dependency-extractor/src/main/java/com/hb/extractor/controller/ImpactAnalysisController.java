package com.hb.extractor.controller;

import com.hb.extractor.dto.*;
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

    @GetMapping("/sync-status")
    public Result<List<Neo4jSyncLogVO>> getSyncStatus() {
        return Result.success(impactAnalysisService.getSyncStatus());
    }
}
