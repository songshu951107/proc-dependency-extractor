package com.hb.extractor.service.impl;

import com.hb.extractor.service.AnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AnalyticsServiceSmokeTest {

    @Autowired AnalyticsService service;

    @Test
    void overviewFieldsPresent() {
        Map<String, Object> ov = service.overview(30);
        assertTrue(ov.containsKey("loginCount"));
        assertTrue(ov.containsKey("activeUsers"));
        assertTrue(ov.containsKey("pageViews"));
        assertTrue(ov.containsKey("avgPageViews"));
    }

    @Test
    void hourDistributionHas24Buckets() {
        assertEquals(24, service.hourDistribution(30).size());
    }

    @Test
    void pageTrendShape() {
        Map<String, Object> pt = service.pageTrend(30, 3);
        assertTrue(pt.containsKey("dates"));
        assertTrue(pt.containsKey("series"));
    }

    @Test
    void illegalDaysClamped() {
        assertNotNull(service.overview(0));
        assertNotNull(service.trend(120));
    }

    @Test
    void drillUserReturnsEventsList() {
        Map<String, Object> d = service.drill("user", 30, null, null, 1L);
        assertTrue(d.get("events") instanceof List);
    }

    @Test
    void userActivityShapeAndOrdering() {
        List<Map<String, Object>> rows = service.userActivity(30, null);
        assertFalse(rows.isEmpty());
        Map<String, Object> first = rows.get(0);
        assertNotNull(first.get("id"));
        assertNotNull(first.get("username"));
        assertNotNull(first.get("loginCount"));
        assertNotNull(first.get("pageViews"));
        assertNotNull(first.get("lastActive"));
        long prev = Long.MAX_VALUE;
        for (Map<String, Object> r : rows) {
            long pv = ((Number) r.get("pageViews")).longValue();
            assertTrue(pv <= prev, "userActivity 应按 pageViews 降序");
            prev = pv;
        }
    }
}
