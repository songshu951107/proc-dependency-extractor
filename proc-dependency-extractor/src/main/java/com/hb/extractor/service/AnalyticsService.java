package com.hb.extractor.service;
import java.util.List; import java.util.Map;
public interface AnalyticsService {
    Map<String, Object> overview(int days);
    List<Map<String, Object>> trend(int days);
    List<Map<String, Object>> hourDistribution(int days);
    List<Map<String, Object>> pageTop(int days);
    Map<String, Object> pageTrend(int days, int limit);
    List<Map<String, Object>> userActivity(int days, String keyword);
    Map<String, Object> drill(String type, int days, String date, String pageName, Long userId);
}
