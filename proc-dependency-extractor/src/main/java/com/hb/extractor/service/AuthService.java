package com.hb.extractor.service;

import com.hb.extractor.dto.ChangePasswordRequest;
import com.hb.extractor.dto.CurrentUserVO;
import com.hb.extractor.dto.LoginRequest;
import com.hb.extractor.dto.LoginResponse;

public interface AuthService {
    LoginResponse login(LoginRequest request, String ip, String userAgent);
    CurrentUserVO currentUser(Long userId);
    void changePassword(Long userId, ChangePasswordRequest request);
}