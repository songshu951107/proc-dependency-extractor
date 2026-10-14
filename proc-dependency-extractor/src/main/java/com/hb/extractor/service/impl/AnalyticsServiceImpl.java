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

    /** 合法档位 {7,30,90}，非法值向上夹取到不小于其的最小档位。 */
    static int normalizeDays(int days) {
        if (days <= 7) return 7;
        if (days <= 30) return 30;
        return 90;
    }

    @Override public Map<String, Object> overview(int days) {
        int d = normalizeDays(days), off = d - 1;
        long loginCount = mapper.loginCount(off);
        long activeUsers = mapper.activeUsers(off);
        long pageViews = mapper.pageViews(off);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loginCount", loginCount);
        m.put("activeUsers", activeUsers);
        m.put("pageViews", pageViews);
        m.put("avgPageViews", activeUsers == 0 ? 0 : Math.round((double) pageViews / activeUsers * 10) / 10.0);
        return m;
    }

    @Override public List<Map<String, Object>> trend(int days) {
        return mapper.trend(normalizeDays(days) - 1);
    }

    @Override public List<Map<String, Object>> hourDistribution(int days) {
        List<Map<String, Object>> raw = mapper.hourDistribution(normalizeDays(days) - 1);
        Map<Integer, Long> byHour = new HashMap<>();
        for (Map<String, Object> r : raw) {
            byHour.put(((Number) r.get("hour")).intValue(), ((Number) r.get("c")).longValue());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hour", h);
            m.put("c", byHour.getOrDefault(h, 0L));
            out.add(m);
        }
        return out;
    }

    @Override public List<Map<String, Object>> pageTop(int days) {
        List<Map<String, Object>> raw = mapper.pageTop(normalizeDays(days) - 1);
        long total = 0;
        for (Map<String, Object> r : raw) total += ((Number) r.get("c")).longValue();
        for (Map<String, Object> r : raw) {
            long c = ((Number) r.get("c")).longValue();
            r.put("pct", total == 0 ? 0 : Math.round((double) c / total * 1000) / 10.0);
        }
        return raw;
    }

    @Override public Map<String, Object> pageTrend(int days, int limit) {
        int d = normalizeDays(days), off = d - 1;
        List<Map<String, Object>> tops = mapper.topPages(off, limit);
        List<String> pages = new ArrayList<>();
        for (Map<String, Object> t : tops) pages.add((String) t.get("name"));
        List<Map<String, Object>> rows = mapper.pageTrend(off, limit);
        // 收集日期（有序）
        List<String> dates = new ArrayList<>();
        Map<String, Map<String, Long>> byPage = new LinkedHashMap<>();
        for (String p : pages) byPage.put(p, new LinkedHashMap<>());
        for (Map<String, Object> r : rows) {
            String p = (String) r.get("pageName");
            String day = String.valueOf(r.get("d"));
            if (!dates.contains(day)) dates.add(day);
            byPage.computeIfAbsent(p, k -> new LinkedHashMap<>())
                  .put(day, ((Number) r.get("c")).longValue());
        }
        Collections.sort(dates);
        List<Map<String, Object>> series = new ArrayList<>();
        for (String p : pages) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", p);
            List<Long> data = new ArrayList<>();
            for (String day : dates) data.add(byPage.get(p).getOrDefault(day, 0L));
            s.put("data", data);
            series.add(s);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dates", dates);
        out.put("series", series);
        return out;
    }

    @Override public List<Map<String, Object>> userActivity(int days, String keyword) {
        return mapper.userActivity(normalizeDays(days) - 1, keyword);
    }

    @Override public Map<String, Object> drill(String type, int days, String date, String pageName, Long userId) {
        int off = normalizeDays(days) - 1;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        switch (type == null ? "" : type) {
            case "day":
                out.put("users", mapper.dayActiveUsers(date));
                out.put("pages", mapper.dayPageTop(date));
                break;
            case "page":
                out.put("users", mapper.pageUsers(pageName, off));
                break;
            case "user":
                out.put("events", mapper.userEvents(userId, off));
                break;
            default:
                out.put("users", Collections.emptyList());
        }
        return out;
    }
}
