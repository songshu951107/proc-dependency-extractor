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
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/parse")
    public Result<ReportInfo> parse(@RequestBody Map<String, String> body) {
        try {
            return Result.success(reportParseService.parseAndSave(body.get("path")));
        } catch (Exception e) {
            log.error("报表解析失败", e);
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
