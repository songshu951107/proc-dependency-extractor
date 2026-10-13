package com.hb.extractor.service.impl;
import com.hb.extractor.mapper.AnalyticsMapper;
import com.hb.extractor.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AnalyticsServiceImpl implements AnalyticsService {
    private final AnalyticsMapper mapper;

    @Override public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("todayLoginCount", mapper.todayLoginCount());
        m.put("todayActiveUsers", mapper.todayActiveUsers());
        m.put("todayPageViews", mapper.todayPageViews());
        m.put("todayLoginFail", mapper.todayLoginFail());
        return m;
    }
    @Override public List<Map<String, Object>> loginTrend() { return mapper.loginTrend30d(); }
    @Override public List<Map<String, Object>> pageTop() { return mapper.pageTop(); }
    @Override public List<Map<String, Object>> userActivity() { return mapper.userActivity(); }
}