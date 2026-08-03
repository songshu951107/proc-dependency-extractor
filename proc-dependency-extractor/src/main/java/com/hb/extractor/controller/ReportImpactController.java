package com.hb.extractor.controller;

import com.hb.extractor.dto.ReportImpactVO;
import com.hb.extractor.dto.Result;
import com.hb.extractor.service.ReportImpactService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/report-impact")
public class ReportImpactController {

    @Autowired
    private ReportImpactService reportImpactService;

    @GetMapping("/by-table")
    public Result<ReportImpactVO> byTable(@RequestParam String schema, @RequestParam String table) {
        return Result.success(reportImpactService.findReportsByTable(schema, table));
    }

    @GetMapping("/by-field")
    public Result<ReportImpactVO> byField(@RequestParam String schema, @RequestParam String table,
                                          @RequestParam String field) {
        return Result.success(reportImpactService.findReportsByField(schema, table, field));
    }

    @GetMapping("/field-source")
    public Result<ReportImpactVO> fieldSource(@RequestParam String reportFile,
                                              @RequestParam String datasetName,
                                              @RequestParam String columnName) {
        return Result.success(reportImpactService.traceFieldSource(reportFile, datasetName, columnName));
    }

    @GetMapping("/cross-impact")
    public Result<ReportImpactVO> crossImpact(@RequestParam String schema, @RequestParam String table,
                                              @RequestParam(required = false) String field) {
        return Result.success(reportImpactService.analyzeCrossImpact(schema, table, field));
    }
}
