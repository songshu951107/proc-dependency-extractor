package com.hb.extractor.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hb.extractor.dto.BatchExtractResult;
import com.hb.extractor.dto.ExtractRequest;
import com.hb.extractor.dto.Result;
import com.hb.extractor.dto.ScriptVO;
import com.hb.extractor.entity.ProcInfo;
import com.hb.extractor.entity.ProcTableDependency;
import com.hb.extractor.service.ProcExtractService;
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

    /**
     * 单文件解析入库
     * POST /api/proc/extract
     * body: {"path": "d:/HB/work_zuoye/jiaoben/dwa_c_agt_deposit_acct_inf.py"}
     */
    @PostMapping("/extract")
    public Result<ProcInfo> extract(@Valid @RequestBody ExtractRequest request) {
        try {
            ProcInfo procInfo = procExtractService.extractAndSave(request.getPath());
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
            return Result.success(result);
        } catch (Exception e) {
            log.error("全量重新解析失败", e);
            return Result.error(e.getMessage());
        }
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
}
