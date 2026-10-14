package com.hb.extractor.controller;
import com.hb.extractor.dto.Result;
import com.hb.extractor.service.AnalyticsService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private final AnalyticsService service;
    public AnalyticsController(AnalyticsService service) { this.service = service; }

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(@RequestParam(defaultValue = "30") int days) {
        return Result.success(service.overview(days));
    }
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "30") int days) {
        return Result.success(service.trend(days));
    }
    @GetMapping("/hour-distribution")
    public Result<List<Map<String, Object>>> hourDistribution(@RequestParam(defaultValue = "30") int days) {
        return Result.success(service.hourDistribution(days));
    }
    @GetMapping("/page-top")
    public Result<List<Map<String, Object>>> pageTop(@RequestParam(defaultValue = "30") int days) {
        return Result.success(service.pageTop(days));
    }
    @GetMapping("/page-trend")
    public Result<Map<String, Object>> pageTrend(@RequestParam(defaultValue = "30") int days,
                                                 @RequestParam(defaultValue = "3") int top) {
        return Result.success(service.pageTrend(days, top));
    }
    @GetMapping("/user-activity")
    public Result<List<Map<String, Object>>> userActivity(@RequestParam(defaultValue = "30") int days,
                                                          @RequestParam(required = false) String keyword) {
        return Result.success(service.userActivity(days, keyword));
    }
    @GetMapping("/drill")
    public Result<Map<String, Object>> drill(@RequestParam String type,
                                             @RequestParam(defaultValue = "30") int days,
                                             @RequestParam(required = false) String date,
                                             @RequestParam(required = false) String pageName,
                                             @RequestParam(required = false) Long userId) {
        return Result.success(service.drill(type, days, date, pageName, userId));
    }
}
