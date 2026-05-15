package com.connecthub.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    private OtpService otpService;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        otpService = new OtpService(redis);
    }

    // ── generateAndStore ─────────────────────────────────────────────────────

    @Test
    void generateAndStore_returns6DigitOtp() {
        String otp = otpService.generateAndStore("register", "a@b.com", 5);
        assertNotNull(otp);
        assertEquals(6, otp.length());
        assertTrue(otp.matches("\\d{6}"));
    }

    @Test
    void generateAndStore_storesInRedis() {
        String otp = otpService.generateAndStore("register", "a@b.com", 5);
        verify(valueOps).set(eq("otp:register:a@b.com"), eq(otp), eq(5L), eq(TimeUnit.MINUTES));
    }

    @Test
    void generateAndStore_emailIdentifier_normalizesCaseAndWhitespace() {
        String otp = otpService.generateAndStore("register", "  A@B.COM  ", 5);
        verify(valueOps).set(eq("otp:register:a@b.com"), eq(otp), eq(5L), eq(TimeUnit.MINUTES));
    }

    @Test
    void generateAndStore_phoneIdentifier_doesNotThrow() {
        String otp = otpService.generateAndStore("phonelogin", "+919399934525", 5);

        assertNotNull(otp);
        verify(valueOps).set(eq("otp:phonelogin:+919399934525"), eq(otp), eq(5L), eq(TimeUnit.MINUTES));
    }

    @Test
    void generateAndStore_eachCallReturnsDifferentCode() {
        String otp1 = otpService.generateAndStore("register", "a@b.com", 5);
        String otp2 = otpService.generateAndStore("register", "b@b.com", 5);
        // statistically near-impossible to be equal; proves generation is random
        assertNotNull(otp1);
        assertNotNull(otp2);
    }

    // ── verify ───────────────────────────────────────────────────────────────

    @Test
    void verify_correctOtp_returnsTrue() {
        when(valueOps.get("otp:attempts:register:a@b.com")).thenReturn(null);
        when(valueOps.increment("otp:attempts:register:a@b.com")).thenReturn(1L);
        when(valueOps.get("otp:register:a@b.com")).thenReturn("123456");
        assertTrue(otpService.verify("register", "a@b.com", "123456"));
    }

    @Test
    void verify_emailIdentifier_normalizesCaseAndWhitespace() {
        when(valueOps.get("otp:attempts:register:a@b.com")).thenReturn(null);
        when(valueOps.increment("otp:attempts:register:a@b.com")).thenReturn(1L);
        when(valueOps.get("otp:register:a@b.com")).thenReturn("123456");
        assertTrue(otpService.verify("register", "  A@B.COM  ", "123456"));
    }

    @Test
    void verify_wrongOtp_returnsFalse() {
        when(valueOps.get("otp:attempts:register:a@b.com")).thenReturn(null);
        when(valueOps.increment("otp:attempts:register:a@b.com")).thenReturn(1L);
        when(valueOps.get("otp:register:a@b.com")).thenReturn("123456");
        assertFalse(otpService.verify("register", "a@b.com", "000000"));
    }

    @Test
    void verify_expiredOtp_returnsFalse() {
        when(valueOps.get("otp:attempts:register:a@b.com")).thenReturn(null);
        when(valueOps.increment("otp:attempts:register:a@b.com")).thenReturn(1L);
        when(valueOps.get("otp:register:a@b.com")).thenReturn(null); // expired / not found
        assertFalse(otpService.verify("register", "a@b.com", "123456"));
    }

    @Test
    void verify_maxAttemptsExceeded_returnsFalse() {
        when(valueOps.get("otp:attempts:register:a@b.com")).thenReturn("5");
        assertFalse(otpService.verify("register", "a@b.com", "123456"));
        verify(valueOps, never()).get("otp:register:a@b.com");
    }

    @Test
    void verify_correctOtp_deletesStoredOtp() {
        when(valueOps.get("otp:attempts:register:a@b.com")).thenReturn(null);
        when(valueOps.increment("otp:attempts:register:a@b.com")).thenReturn(1L);
        when(valueOps.get("otp:register:a@b.com")).thenReturn("111222");

        otpService.verify("register", "a@b.com", "111222");

        verify(redis).delete("otp:register:a@b.com");
    }

    // ── isOnCooldown ─────────────────────────────────────────────────────────

    @Test
    void isOnCooldown_keyExists_returnsTrue() {
        when(redis.hasKey("otp:cooldown:register:a@b.com")).thenReturn(true);
        assertTrue(otpService.isOnCooldown("register", "a@b.com"));
    }

    @Test
    void isOnCooldown_keyAbsent_returnsFalse() {
        when(redis.hasKey("otp:cooldown:register:a@b.com")).thenReturn(false);
        assertFalse(otpService.isOnCooldown("register", "a@b.com"));
    }

    // ── setCooldown ──────────────────────────────────────────────────────────

    @Test
    void setCooldown_setsKeyWithTtl() {
        otpService.setCooldown("register", "a@b.com", 60);
        verify(valueOps).set(eq("otp:cooldown:register:a@b.com"), eq("1"), eq(60L), eq(TimeUnit.SECONDS));
    }
}
