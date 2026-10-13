package com.hb.extractor.service;
import java.util.List; import java.util.Map;
public interface AnalyticsService {
    Map<String, Object> overview();
    List<Map<String, Object>> loginTrend();
    List<Map<String, Object>> pageTop();
    List<Map<String, Object>> userActivity();
}