package com.hb.extractor.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class PasswordConfigTest {

    private final BCryptPasswordEncoder encoder = new PasswordConfig().passwordEncoder();

    @Test
    void defaultPasswordHashMatches() {
        assertTrue(encoder.matches("1234.Com", encoder.encode("1234.Com")));
    }

    @Test
    void wrongPasswordRejected() {
        assertFalse(encoder.matches("wrong", encoder.encode("1234.Com")));
    }
}