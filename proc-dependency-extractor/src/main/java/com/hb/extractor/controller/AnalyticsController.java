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

    @GetMapping("/overview") public Result<Map<String, Object>> overview() { return Result.success(service.overview()); }
    @GetMapping("/login-trend") public Result<List<Map<String, Object>>> loginTrend() { return Result.success(service.loginTrend()); }
    @GetMapping("/page-top") public Result<List<Map<String, Object>>> pageTop() { return Result.success(service.pageTop()); }
    @GetMapping("/user-activity") public Result<List<Map<String, Object>>> userActivity() { return Result.success(service.userActivity()); }
}