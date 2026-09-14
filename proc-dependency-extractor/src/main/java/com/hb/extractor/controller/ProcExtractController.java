package com.hb.extractor.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hb.extractor.dto.BatchExtractResult;
import com.hb.extractor.dto.ExtractRequest;
import com.hb.extractor.dto.Result;
import com.hb.extractor.dto.ScriptVO;
import com.hb.extractor.entity.ProcInfo;
import com.hb.extractor.entity.ProcParseFail;
import com.hb.extractor.entity.ProcTableDependency;
import com.hb.extractor.entity.RebuildTaskEntity;
import com.hb.extractor.service.Neo4jSyncService;
import com.hb.extractor.service.ProcExtractService;
import com.hb.extractor.service.impl.AsyncExtractService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;

/**
 * 存储过程依赖提取 Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/proc")
public class ProcExtractController {

    @Autowired
    private ProcExtractService procExtractService;

    @Autowired
    private Neo4jSyncService neo4jSyncService;

    @Autowired
    private AsyncExtractService asyncExtractService;

    /**
     * 单文件解析入库
     * POST /api/proc/extract
     * body: {"path": "d:/HB/work_zuoye/jiaoben/dwa_c_agt_deposit_acct_inf.py"}
     */
    @PostMapping("/extract")
    public Result<ProcInfo> extract(@Valid @RequestBody ExtractRequest request) {
        try {
            ProcInfo procInfo = procExtractService.extractAndSave(request.getPath());
            // Neo4j 同步（MySQL 已提交，失败不影响主流程）
            syncNeo4jSafe(procInfo.getProcName());
            return Result.success(procInfo);
        } catch (Exception e) {
            log.error("单文件解析失败", e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 目录批量解析入库
     * POST /api/proc/extract/batch
     * body: {"path": "d:/HB/work_zuoye/jiaoben"}
     */
    @PostMapping("/extract/batch")
    public Result<BatchExtractResult> extractBatch(@Valid @RequestBody ExtractRequest request) {
        try {
            BatchExtractResult result = procExtractService.extractAndSaveBatch(request.getPath());
            // MySQL 已提交，逐个同步 Neo4j（失败不影响主流程）
            syncNeo4jBatchSafe(result);
            return Result.success(result);
        } catch (Exception e) {
            log.error("批量解析失败", e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 重新解析全部已存在存储过程
     * POST /api/proc/extract/all
     */
    @PostMapping("/extract/all")
    public Result<BatchExtractResult> reExtractAll() {
        try {
            BatchExtractResult result = procExtractService.reExtractAll();
            // MySQL 已提交，逐个同步 Neo4j（失败不影响主流程）
            syncNeo4jBatchSafe(result);
            return Result.success(result);
        } catch (Exception e) {
            log.error("全量重新解析失败", e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 异步重新解析全部已存在存储过程（后台执行，避免大库请求超时）
     * POST /api/proc/extract/all/async
     * 立即返回 taskId，前端轮询进度查询接口。
     */
    @PostMapping("/extract/all/async")
    public Result<String> reExtractAllAsync() {
        String taskId = asyncExtractService.start();
        return Result.success(taskId);
    }

    /**
     * 查询异步全量解析任务进度
     * GET /api/proc/extract/all/task/{taskId}
     */
    @GetMapping("/extract/all/task/{taskId}")
    public Result<RebuildTaskEntity> reExtractAllTask(@PathVariable String taskId) {
        RebuildTaskEntity task = asyncExtractService.get(taskId);
        if (task == null) {
            return Result.error(404, "任务不存在或已清除");
        }
        return Result.success(task);
    }

    /**
     * 查询最近一次全量解析任务进度（页面常驻展示用）
     * GET /api/proc/extract/all/task/latest
     */
    @GetMapping("/extract/all/task/latest")
    public Result<RebuildTaskEntity> reExtractAllLatest() {
        return Result.success(asyncExtractService.latest());
    }

    /**
     * 上传 py 文件解析入库(支持多文件)
     * POST /api/proc/extract/upload
     * form-data: files
     */
    @PostMapping("/extract/upload")
    public Result<BatchExtractResult> upload(@RequestParam("files") MultipartFile[] files) {
        try {
            if (files == null || files.length == 0) {
                return Result.error("请选择至少一个 .py 文件");
            }
            List<MultipartFile> fileList = Arrays.asList(files);
            BatchExtractResult result = procExtractService.extractAndSaveUploaded(fileList);
            // MySQL 已提交，逐个同步 Neo4j（失败不影响主流程）
            syncNeo4jBatchSafe(result);
            return Result.success(result);
        } catch (Exception e) {
            log.error("上传解析失败", e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 分页查询存储过程列表
     * GET /api/proc/list?pageNum=1&pageSize=10&procName=xxx
     */
    @GetMapping("/list")
    public Result<IPage<ProcInfo>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String procName) {
        IPage<ProcInfo> page = procExtractService.listProcs(pageNum, pageSize, procName);
        return Result.success(page);
    }

    /**
     * 查询某存储过程的依赖表
     * GET /api/proc/{procName}/tables
     */
    @GetMapping("/{procName}/tables")
    public Result<List<ProcTableDependency>> tables(@PathVariable String procName) {
        List<ProcTableDependency> list = procExtractService.listDependencies(procName);
        return Result.success(list);
    }

    /**
     * 删除存储过程及其依赖
     * DELETE /api/proc/{procName}
     */
    @DeleteMapping("/{procName}")
    public Result<Boolean> delete(@PathVariable String procName) {
        boolean ok = procExtractService.deleteProc(procName);
        // 同步删除 Neo4j 数据（MySQL 已提交，失败不影响主流程）
        if (ok) {
            try {
                neo4jSyncService.deleteProcData(procName);
            } catch (Exception e) {
                log.warn("删除 Neo4j 数据失败 proc={}: {}", procName, e.getMessage());
            }
        }
        return Result.success(ok);
    }

    /**
     * 查询脚本内容
     * GET /api/proc/{procName}/script
     */
    @GetMapping("/{procName}/script")
    public Result<ScriptVO> script(@PathVariable String procName) {
        ScriptVO vo = procExtractService.getScript(procName);
        if (vo == null) {
            return Result.error(404, "存储过程不存在");
        }
        return Result.success(vo);
    }

    /**
     * 分页查询解析失败清单
     * GET /api/proc/parse-fails?pageNum=1&pageSize=10&procName=xxx&onlyUnresolved=true
     */
    @GetMapping("/parse-fails")
    public Result<IPage<ProcParseFail>> parseFails(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String procName,
            @RequestParam(required = false) Boolean onlyUnresolved) {
        IPage<ProcParseFail> page = procExtractService.listParseFails(pageNum, pageSize, procName, onlyUnresolved);
        return Result.success(page);
    }

    /**
     * 标记一条解析失败为已处理
     * POST /api/proc/parse-fail/{id}/resolve
     */
    @PostMapping("/parse-fail/{id}/resolve")
    public Result<Boolean> resolveParseFail(@PathVariable Long id) {
        boolean ok = procExtractService.resolveParseFail(id);
        if (!ok) {
            return Result.error(404, "记录不存在");
        }
        return Result.success(true);
    }

    /**
     * 重新解析单个存储过程
     * POST /api/proc/{procName}/reparse
     */
    @PostMapping("/{procName}/reparse")
    public Result<ProcInfo> reparse(@PathVariable String procName) {
        try {
            ProcInfo procInfo = procExtractService.reparse(procName);
            syncNeo4jSafe(procInfo.getProcName());
            return Result.success(procInfo);
        } catch (Exception e) {
            log.error("重新解析存储过程失败 procName={}", procName, e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 同步单个 proc 到 Neo4j，失败仅告警不影响主流程（Best Effort）。
     */
    private void syncNeo4jSafe(String procName) {
        try {
            neo4jSyncService.syncProc(procName);
        } catch (Exception e) {
            log.warn("Neo4j 同步失败 proc={}: {}", procName, e.getMessage());
        }
    }

    /**
     * 批量同步成功的 proc 到 Neo4j，逐个 try-catch，失败仅告警不影响主流程（Best Effort）。
     */
    private void syncNeo4jBatchSafe(BatchExtractResult result) {
        if (result == null || result.getSuccessProcNames() == null) {
            return;
        }
        for (String procName : result.getSuccessProcNames()) {
            syncNeo4jSafe(procName);
        }
    }
}
