package com.hb.extractor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hb.extractor.dto.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void loginSuccessReturnsToken() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername("admin"); req.setPassword("1234.Com");
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").exists());
    }

    @Test
    void loginFailReturns400() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername("admin"); req.setPassword("wrong");
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void noTokenHitsProtectedApi() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(jsonPath("$.code").value(401));
    }
}