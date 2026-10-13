package com.hb.extractor.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private final JwtUtil jwtUtil =
        new JwtUtil("hb-extractor-2026-secret-key-please-change-me-32", 12);

    @Test
    void generateAndParse() {
        String token = jwtUtil.generate(7L, "zhangsan", "USER");
        Claims claims = jwtUtil.parse(token);
        assertEquals("zhangsan", claims.getSubject());
        assertEquals(7L, ((Number) claims.get("uid")).longValue());
        assertEquals("USER", claims.get("role"));
    }

    @Test
    void tamperedTokenRejected() {
        String token = jwtUtil.generate(1L, "admin", "ADMIN");
        assertThrows(RuntimeException.class, () -> jwtUtil.parse(token + "x"));
    }

    @Test
    void expiredTokenRejected() {
        JwtUtil shortLived = new JwtUtil("hb-extractor-2026-secret-key-please-change-me-32", 12);
        String token = shortLived.generateWithExpiry(1L, "admin", "ADMIN", System.currentTimeMillis() - 1000);
        assertThrows(RuntimeException.class, () -> jwtUtil.parse(token));
    }
}