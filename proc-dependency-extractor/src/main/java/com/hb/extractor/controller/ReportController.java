package com.hb.extractor.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hb.extractor.dto.Result;
import com.hb.extractor.entity.ReportCellBinding;
import com.hb.extractor.entity.ReportDataset;
import com.hb.extractor.entity.ReportInfo;
import com.hb.extractor.mapper.ReportCellBindingMapper;
import com.hb.extractor.mapper.ReportDatasetMapper;
import com.hb.extractor.service.ReportParseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/report")
public class ReportController {

    @Autowired
    private ReportParseService reportParseService;
    @Autowired
    private ReportDatasetMapper reportDatasetMapper;
    @Autowired
    private ReportCellBindingMapper reportCellBindingMapper;

    /** 报表脚本目录（配置项 extractor.report.dir） */
    @Value("${extractor.report.dir:d:/HB/work_zuoye/reports}")
    private String reportDir;

    /**
     * 返回配置的报表脚本目录（extractor.report.dir）。
     * GET /api/report/config/dir
     * 前端用于动态展示"重新解析/上传落地"的默认目录。
     */
    @GetMapping("/config/dir")
    public Result<String> configDir() {
        return Result.success(reportDir);
    }

    @PostMapping("/parse")
    public Result<ReportInfo> parse(@RequestBody Map<String, String> body) {
        try {
            return Result.success(reportParseService.parseAndSave(body.get("path")));
        } catch (Exception e) {
            log.error("报表解析失败", e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 上传报表文件并解析。文件会保存到 reports 目录后复用 parseAndSave 逻辑。
     * 若同名文件已存在会被覆盖，保证重复上传重解析时数据一致。
     */
    @PostMapping("/parse/upload")
    public Result<ReportInfo> parseUpload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.error("上传文件为空");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            return Result.error("文件名为空");
        }
        try {
            Path reportsDir = Paths.get(reportDir);
            Files.createDirectories(reportsDir);
            Path target = reportsDir.resolve(originalName);
            // 覆盖同名文件
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return Result.success(reportParseService.parseAndSave(target.toString()));
        } catch (Exception e) {
            log.error("上传解析失败: {}", originalName, e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/parse/batch")
    public Result<List<ReportInfo>> parseBatch(@RequestBody Map<String, String> body) {
        try {
            return Result.success(reportParseService.parseBatch(body.get("path")));
        } catch (Exception e) {
            log.error("批量解析失败", e);
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/parse/all")
    public Result<List<ReportInfo>> reParseAll() {
        try {
            return Result.success(reportParseService.reParseAll());
        } catch (Exception e) {
            log.error("全量重新解析失败", e);
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/list")
    public Result<IPage<ReportInfo>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String name) {
        return Result.success(reportParseService.listReports(pageNum, pageSize, name));
    }

    @GetMapping("/{file}")
    public Result<Map<String, Object>> detail(@PathVariable String file) {
        ReportInfo info = reportParseService.getReportDetail(file);
        if (info == null) return Result.error(404, "报表不存在");
        List<ReportDataset> datasets = reportDatasetMapper.selectList(
                new LambdaQueryWrapper<ReportDataset>().eq(ReportDataset::getReportFile, file));
        List<ReportCellBinding> bindings = reportCellBindingMapper.selectList(
                new LambdaQueryWrapper<ReportCellBinding>().eq(ReportCellBinding::getReportFile, file));
        Map<String, Object> result = new HashMap<>();
        result.put("info", info);
        result.put("datasets", datasets);
        result.put("bindings", bindings);
        return Result.success(result);
    }

    @DeleteMapping("/{file}")
    public Result<Boolean> delete(@PathVariable String file) {
        return Result.success(reportParseService.deleteReport(file));
    }
}
