package com.hb.extractor.controller;

import com.hb.extractor.dto.ImpactAnalysisVO;
import com.hb.extractor.dto.ReportImpactVO;
import com.hb.extractor.dto.Result;
import com.hb.extractor.service.ReportImpactService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/report-impact")
public class ReportImpactController {

    @Autowired
    private ReportImpactService reportImpactService;

    @GetMapping("/fields")
    public Result<List<String>> fields(@RequestParam String schema, @RequestParam String table) {
        return Result.success(reportImpactService.getFields(schema, table));
    }

    @GetMapping("/by-table")
    public Result<ReportImpactVO> byTable(@RequestParam String schema, @RequestParam String table) {
        return Result.success(reportImpactService.findReportsByTable(schema, table));
    }

    @GetMapping("/by-field")
    public Result<ReportImpactVO> byField(@RequestParam String schema, @RequestParam String table,
                                          @RequestParam String field) {
        return Result.success(reportImpactService.findReportsByField(schema, table, field));
    }

    @GetMapping("/by-header")
    public Result<ReportImpactVO> byHeader(@RequestParam String headerText) {
        return Result.success(reportImpactService.findReportsByHeaderText(headerText));
    }

    /** 场景4：报表列溯源 - 获取指定报表的全部表头列汉字 */
    @GetMapping("/report-headers")
    public Result<List<String>> reportHeaders(@RequestParam String reportFile) {
        return Result.success(reportImpactService.getReportHeaders(reportFile));
    }

    /** 场景4：报表列溯源 - 根据报表+表头列追溯上游字段级链路 */
    @GetMapping("/trace-lineage")
    public Result<ImpactAnalysisVO> traceLineage(@RequestParam String reportFile,
                                                  @RequestParam String headerText) {
        try {
            return Result.success(reportImpactService.traceReportLineage(reportFile, headerText));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("报表列溯源失败: {}", e.getMessage());
            return Result.error(e.getMessage());
        }
    }
}
