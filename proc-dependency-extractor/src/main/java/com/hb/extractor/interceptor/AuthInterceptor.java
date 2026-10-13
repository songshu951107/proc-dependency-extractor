package com.hb.extractor.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.annotation.RequireAdmin;
import com.hb.extractor.context.UserContext;
import com.hb.extractor.dto.Result;
import com.hb.extractor.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return write(response, 401, "未登录或登录已过期");
        }
        try {
            Claims claims = jwtUtil.parse(header.substring(7));
            UserContext.CurrentUser user = new UserContext.CurrentUser();
            user.setUserId(((Number) claims.get("uid")).longValue());
            user.setUsername(claims.getSubject());
            user.setRoleCode((String) claims.get("role"));
            UserContext.set(user);
        } catch (Exception e) {
            return write(response, 401, "未登录或登录已过期");
        }
        if (handler instanceof HandlerMethod hm) {
            boolean needAdmin = hm.hasMethodAnnotation(RequireAdmin.class)
                    || hm.getBeanType().isAnnotationPresent(RequireAdmin.class);
            if (needAdmin && !UserContext.isAdmin()) {
                return write(response, 403, "无权限");
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        UserContext.clear();
    }

    private boolean write(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, message)));
        response.getWriter().flush();
        return false;
    }
}