package com.hb.extractor.controller;

import com.hb.extractor.context.UserContext;
import com.hb.extractor.dto.*;
import com.hb.extractor.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    public AuthController(AuthService authService) { this.authService = authService; }

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request, HttpServletRequest http) {
        return Result.success(authService.login(request, clientIp(http), http.getHeader("User-Agent")));
    }

    @GetMapping("/me")
    public Result<CurrentUserVO> me() {
        return Result.success(authService.currentUser(UserContext.get().getUserId()));
    }

    @PostMapping("/change-password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest request) {
        authService.changePassword(UserContext.get().getUserId(), request);
        return Result.success();
    }

    @PostMapping("/logout")
    public Result<Void> logout() { return Result.success(); }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
    }
}