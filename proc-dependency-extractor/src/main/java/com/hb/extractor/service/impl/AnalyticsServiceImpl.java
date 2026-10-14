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

    /** 合法档位 {7,30,90}，非法值向上夹取到不小于其的最小档位。 */
    static int normalizeDays(int days) {
        if (days <= 7) return 7;
        if (days <= 30) return 30;
        return 90;
    }
}