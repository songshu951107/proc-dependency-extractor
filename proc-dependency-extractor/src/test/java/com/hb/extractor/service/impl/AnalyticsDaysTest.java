package com.hb.extractor.service.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AnalyticsDaysTest {

    @Test
    void keepsLegalValues() {
        assertEquals(7, AnalyticsServiceImpl.normalizeDays(7));
        assertEquals(30, AnalyticsServiceImpl.normalizeDays(30));
        assertEquals(90, AnalyticsServiceImpl.normalizeDays(90));
    }

    @Test
    void clampsIllegalValuesToNearestLowerOrEqual() {
        assertEquals(7, AnalyticsServiceImpl.normalizeDays(0));
        assertEquals(7, AnalyticsServiceImpl.normalizeDays(5));
        assertEquals(30, AnalyticsServiceImpl.normalizeDays(15));
        assertEquals(90, AnalyticsServiceImpl.normalizeDays(45));
        assertEquals(90, AnalyticsServiceImpl.normalizeDays(120));
    }
}
