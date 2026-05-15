package com.connecthub.auth.config;

import com.connecthub.auth.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtUtilTest {

    private JwtUtil jwtUtil;
    private User user;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "accessExpiry", 86_400_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiry", 604_800_000L);

        user = User.builder()
                .userId(42)
                .email("user@example.com")
                .username("user42")
                .role("USER")
                .subscriptionTier("FREE")
                .build();
    }

    @Test
    void generateAccessToken_acceptsBase64EncodedSecret() {
        String secret = Base64.getEncoder()
                .encodeToString("base64-secret-that-is-long-enough-for-hs256-signing".getBytes(StandardCharsets.UTF_8));
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", secret);

        String token = jwtUtil.generateAccessToken(user);

        assertTrue(jwtUtil.isValid(token));
        assertEquals(42, jwtUtil.getUserId(token));
    }

    @Test
    void generateAccessToken_acceptsPlainTextSecret() {
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", "plain-text-secret-that-is-long-enough-for-hs256");

        String token = jwtUtil.generateAccessToken(user);

        assertTrue(jwtUtil.isValid(token));
        assertEquals(42, jwtUtil.getUserId(token));
    }

    @Test
    void generateAccessToken_acceptsShortPlainTextSecret() {
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", "secret");

        String token = jwtUtil.generateAccessToken(user);

        assertTrue(jwtUtil.isValid(token));
        assertEquals(42, jwtUtil.getUserId(token));
    }
}
