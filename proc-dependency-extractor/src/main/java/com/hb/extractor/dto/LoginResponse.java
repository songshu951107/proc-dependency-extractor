package com.hb.extractor.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private boolean mustChangePwd;
    private CurrentUserVO user;
}