package com.hb.extractor.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.annotation.RequireAdmin;
import com.hb.extractor.context.UserContext;
import com.hb.extractor.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthInterceptorTest {

    private final JwtUtil jwtUtil = new JwtUtil("hb-extractor-2026-secret-key-please-change-me-32", 12);
    private final AuthInterceptor interceptor = new AuthInterceptor(jwtUtil, new ObjectMapper());

    @AfterEach
    void clear() { UserContext.clear(); }

    @Test
    void noTokenReturns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/users");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean ok = interceptor.preHandle(req, resp, new HandlerMethod(this, Object.class.getMethod("toString")));
        assertFalse(ok);
        // HTTP 状态保持 200,业务码放在响应体 Result.code(与前端 request.js 判定一致)
        assertEquals(200, resp.getStatus());
        assertEquals(401, new ObjectMapper().readTree(resp.getContentAsString()).get("code").asInt());
        assertNull(UserContext.get(), "401 拒绝后不应残留身份");
    }

    @Test
    void validTokenFillsContext() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/users");
        req.addHeader("Authorization", "Bearer " + jwtUtil.generate(5L, "zhangsan", "USER"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean ok = interceptor.preHandle(req, resp, new HandlerMethod(this, Object.class.getMethod("toString")));
        assertTrue(ok);
        assertEquals("zhangsan", UserContext.get().getUsername());
    }

    @Test
    void nonAdminOnRequireAdminReturns403AndClearsContext() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/admin/users");
        req.addHeader("Authorization", "Bearer " + jwtUtil.generate(9L, "lisi", "USER"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean ok = interceptor.preHandle(req, resp,
                new HandlerMethod(new AdminOnlyHandler(), AdminOnlyHandler.class.getMethod("adminOnly")));
        assertFalse(ok);
        assertEquals(200, resp.getStatus());
        assertEquals(403, new ObjectMapper().readTree(resp.getContentAsString()).get("code").asInt());
        assertNull(UserContext.get(), "403 拒绝后不应残留身份(ThreadLocal 泄漏)");
    }

    static class AdminOnlyHandler {
        @RequireAdmin
        public void adminOnly() {
        }
    }
}