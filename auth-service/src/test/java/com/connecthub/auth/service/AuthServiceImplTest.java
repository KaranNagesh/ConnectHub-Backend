package com.connecthub.auth.service;

import com.connecthub.auth.config.JwtUtil;
import com.connecthub.auth.dto.*;
import com.connecthub.auth.entity.User;
import com.connecthub.auth.exception.*;
import com.connecthub.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private OtpService otpService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private EmailEventPublisher emailPublisher;
    @Mock
    private UserProfileCacheService profileCache;
    @Mock
    private org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;
    @InjectMocks
    private AuthServiceImpl authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder().userId(1).username("testuser").email("test@example.com")
                .passwordHash("hashed").fullName("Test User").status("OFFLINE")
                .role("USER").active(true).emailVerified(true).provider("LOCAL").build();
    }

    // ── register ─────────────────────────────────────────────────────────────

    @Test
    void register_success() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("newuser");
        req.setEmail("new@example.com");
        req.setPassword("Test@1234");
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(userRepository.existsByUsername(any())).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(otpService.isOnCooldown("register", "new@example.com")).thenReturn(false);
        when(otpService.generateAndStore("register", "new@example.com", 5)).thenReturn("123456");
        when(userRepository.save(any())).thenAnswer(i -> {
            User u = i.getArgument(0);
            u.setUserId(2);
            return u;
        });
        ApiResponse<String> resp = authService.register(req);

        assertTrue(resp.isSuccess());
        assertEquals("new@example.com", resp.getData());
        assertEquals("Registration successful. Please verify your email with the OTP we sent.", resp.getMessage());
        verify(userRepository).save(argThat(user -> !user.isEmailVerified()));
        verify(otpService).generateAndStore("register", "new@example.com", 5);
        verify(otpService).setCooldown("register", "new@example.com", 60);
        verify(emailPublisher).sendOtpEmail("new@example.com", "123456", "registration");
    }

    @Test
    void register_normalizesEmailBeforeSavingAndSendingOtp() {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("newuser");
        req.setEmail("  New@Example.COM  ");
        req.setPassword("Test@1234");
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(userRepository.existsByUsername(any())).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(otpService.isOnCooldown("register", "new@example.com")).thenReturn(false);
        when(otpService.generateAndStore("register", "new@example.com", 5)).thenReturn("123456");
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ApiResponse<String> resp = authService.register(req);

        assertEquals("new@example.com", resp.getData());
        verify(userRepository).save(argThat(user -> "new@example.com".equals(user.getEmail())));
        verify(emailPublisher).sendOtpEmail("new@example.com", "123456", "registration");
    }

    @Test
    void register_duplicateEmail_throws() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("dup@test.com");
        req.setUsername("u");
        req.setPassword("P@ss1234");
        User verified = User.builder().email("dup@test.com").emailVerified(true).build();
        when(userRepository.findByEmail("dup@test.com")).thenReturn(Optional.of(verified));
        assertThrows(DuplicateResourceException.class, () -> authService.register(req));
    }

    @Test
    void register_existingUnverifiedEmail_resendsOtp() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("test@example.com");
        req.setUsername("testuser");
        req.setPassword("P@ss1234");
        testUser.setEmailVerified(false);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(otpService.isOnCooldown("register", "test@example.com")).thenReturn(false);
        when(otpService.generateAndStore("register", "test@example.com", 5)).thenReturn("654321");

        ApiResponse<String> resp = authService.register(req);

        assertTrue(resp.isSuccess());
        assertEquals("test@example.com", resp.getData());
        verify(emailPublisher).sendOtpEmail("test@example.com", "654321", "registration");
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_existingUnverifiedEmailOnCooldown_returnsSuccessWithoutResending() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("test@example.com");
        req.setUsername("testuser");
        req.setPassword("P@ss1234");
        testUser.setEmailVerified(false);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(otpService.isOnCooldown("register", "test@example.com")).thenReturn(true);
        when(otpService.getCooldownRemaining("register", "test@example.com")).thenReturn(30L);

        ApiResponse<String> resp = authService.register(req);

        assertTrue(resp.isSuccess());
        assertEquals(30, resp.getCooldownSeconds());
        verifyNoInteractions(emailPublisher);
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_duplicateUsername_throws() {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("new@test.com");
        req.setUsername("taken");
        req.setPassword("P@ss1234");
        when(userRepository.findByEmail("new@test.com")).thenReturn(Optional.empty());
        when(userRepository.existsByUsername("taken")).thenReturn(true);
        assertThrows(DuplicateResourceException.class, () -> authService.register(req));
    }

    // ── verifyRegistrationOtp ────────────────────────────────────────────────

    @Test
    void verifyRegistrationOtp_success() {
        OtpVerifyRequest req = new OtpVerifyRequest();
        req.setEmail("test@example.com");
        req.setOtp("123456");
        testUser.setEmailVerified(false);
        when(otpService.verify("register", "test@example.com", "123456")).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(userRepository.save(any())).thenReturn(testUser);
        when(jwtUtil.generateAccessToken(any())).thenReturn("at");
        when(jwtUtil.generateRefreshToken(any())).thenReturn("rt");
        when(jwtUtil.getAccessExpiry()).thenReturn(86400000L);

        AuthResponse resp = authService.verifyRegistrationOtp(req);

        assertNotNull(resp.getAccessToken());
        assertTrue(testUser.isEmailVerified());
    }

    @Test
    void verifyRegistrationOtp_normalizesEmailBeforeVerifying() {
        OtpVerifyRequest req = new OtpVerifyRequest();
        req.setEmail("  Test@Example.COM  ");
        req.setOtp("123456");
        testUser.setEmailVerified(false);
        when(otpService.verify("register", "test@example.com", "123456")).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(userRepository.save(any())).thenReturn(testUser);
        when(jwtUtil.generateAccessToken(any())).thenReturn("at");
        when(jwtUtil.generateRefreshToken(any())).thenReturn("rt");

        AuthResponse resp = authService.verifyRegistrationOtp(req);

        assertNotNull(resp.getAccessToken());
        verify(userRepository).findByEmail("test@example.com");
    }

    @Test
    void verifyRegistrationOtp_invalidOtp_throws() {
        OtpVerifyRequest req = new OtpVerifyRequest();
        req.setEmail("test@example.com");
        req.setOtp("000000");
        when(otpService.verify("register", "test@example.com", "000000")).thenReturn(false);
        assertThrows(BadRequestException.class, () -> authService.verifyRegistrationOtp(req));
    }

    // ── resendRegistrationOtp ────────────────────────────────────────────────

    @Test
    void resendOtp_success() {
        testUser.setEmailVerified(false);
        when(otpService.isOnCooldown("register", "test@example.com")).thenReturn(false);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(otpService.generateAndStore(any(), any(), anyInt())).thenReturn("654321");

        ApiResponse<Void> resp = authService.resendRegistrationOtp("test@example.com");

        assertTrue(resp.isSuccess());
        verify(emailPublisher).sendOtpEmail(eq("test@example.com"), eq("654321"), eq("registration"));
    }

    @Test
    void resendOtp_onCooldown_returnsFailure() {
        when(otpService.isOnCooldown("register", "test@example.com")).thenReturn(true);
        when(otpService.getCooldownRemaining("register", "test@example.com")).thenReturn(45L);

        ApiResponse<Void> resp = authService.resendRegistrationOtp("test@example.com");

        assertFalse(resp.isSuccess());
        assertEquals(45, resp.getCooldownSeconds());
    }

    @Test
    void resendOtp_alreadyVerified_throws() {
        testUser.setEmailVerified(true);
        when(otpService.isOnCooldown(any(), any())).thenReturn(false);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        assertThrows(BadRequestException.class, () -> authService.resendRegistrationOtp("test@example.com"));
    }

    // ── login ────────────────────────────────────────────────────────────────

    @Test
    void login_success() {
        LoginRequest req = new LoginRequest();
        req.setEmail("test@example.com");
        req.setPassword("Test@1234");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("Test@1234", "hashed")).thenReturn(true);
        when(jwtUtil.generateAccessToken(any())).thenReturn("access");
        when(jwtUtil.generateRefreshToken(any())).thenReturn("refresh");
        when(jwtUtil.getAccessExpiry()).thenReturn(86400000L);

        AuthResponse resp = authService.login(req);

        assertEquals("access", resp.getAccessToken());
        assertEquals("refresh", resp.getRefreshToken());
    }

    @Test
    void login_wrongPassword_throws() {
        LoginRequest req = new LoginRequest();
        req.setEmail("test@example.com");
        req.setPassword("wrong");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);
        assertThrows(UnauthorizedException.class, () -> authService.login(req));
    }

    @Test
    void login_suspendedAccount_throws() {
        testUser.setActive(false);
        LoginRequest req = new LoginRequest();
        req.setEmail("test@example.com");
        req.setPassword("any");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        assertThrows(UnauthorizedException.class, () -> authService.login(req));
    }

    @Test
    void login_unverifiedEmail_throws() {
        testUser.setEmailVerified(false);
        LoginRequest req = new LoginRequest();
        req.setEmail("test@example.com");
        req.setPassword("any");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        assertThrows(UnauthorizedException.class, () -> authService.login(req));
    }

    @Test
    void login_oauthUser_throws() {
        testUser.setProvider("GOOGLE");
        LoginRequest req = new LoginRequest();
        req.setEmail("test@example.com");
        req.setPassword("any");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        assertThrows(UnauthorizedException.class, () -> authService.login(req));
    }

    @Test
    void login_userNotFound_throws() {
        LoginRequest req = new LoginRequest();
        req.setEmail("ghost@test.com");
        req.setPassword("any");
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());
        assertThrows(UnauthorizedException.class, () -> authService.login(req));
    }

    // ── logout ───────────────────────────────────────────────────────────────

    @Test
    void logout_blacklistsToken() {
        when(redis.opsForValue()).thenReturn(valueOps);
        authService.logout("Bearer mytoken");
        verify(valueOps).set(eq("token:blacklist:mytoken"), eq("1"), anyLong(), any());
    }

    @Test
    void logout_tokenWithoutBearer_stillBlacklisted() {
        when(redis.opsForValue()).thenReturn(valueOps);
        authService.logout("rawtoken");
        verify(valueOps).set(eq("token:blacklist:rawtoken"), eq("1"), anyLong(), any());
    }

    // ── validateToken ────────────────────────────────────────────────────────

    @Test
    void validateToken_blacklisted_returnsFalse() {
        when(redis.hasKey("token:blacklist:tok")).thenReturn(true);
        assertFalse(authService.validateToken("tok"));
    }

    @Test
    void validateToken_valid_returnsTrue() {
        when(redis.hasKey("token:blacklist:tok")).thenReturn(false);
        when(jwtUtil.isValid("tok")).thenReturn(true);
        assertTrue(authService.validateToken("tok"));
    }

    // ── refreshToken ─────────────────────────────────────────────────────────

    @Test
    void refreshToken_valid_returnsNewTokens() {
        when(jwtUtil.isValid("rt")).thenReturn(true);
        when(jwtUtil.getUserId("rt")).thenReturn(1);
        when(userRepository.findById(1)).thenReturn(Optional.of(testUser));
        when(jwtUtil.generateAccessToken(any())).thenReturn("new-access");
        when(jwtUtil.generateRefreshToken(any())).thenReturn("new-refresh");
        when(jwtUtil.getAccessExpiry()).thenReturn(86400000L);

        AuthResponse resp = authService.refreshToken("rt");
        assertEquals("new-access", resp.getAccessToken());
    }

    @Test
    void refreshToken_invalid_throws() {
        when(jwtUtil.isValid("bad")).thenReturn(false);
        assertThrows(UnauthorizedException.class, () -> authService.refreshToken("bad"));
    }

    // ── forgotPassword ───────────────────────────────────────────────────────

    @Test
    void forgotPassword_knownEmail_sendsOtp() {
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.setEmail("test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(otpService.isOnCooldown("reset", "test@example.com")).thenReturn(false);
        when(otpService.generateAndStore(any(), any(), anyInt())).thenReturn("999888");

        ApiResponse<Void> resp = authService.forgotPassword(req);

        assertTrue(resp.isSuccess());
        verify(emailPublisher).sendOtpEmail(eq("test@example.com"), eq("999888"), eq("password_reset"));
    }

    @Test
    void forgotPassword_unknownEmail_stillReturnsSuccess() {
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.setEmail("ghost@x.com");
        when(userRepository.findByEmail("ghost@x.com")).thenReturn(Optional.empty());

        ApiResponse<Void> resp = authService.forgotPassword(req);

        assertTrue(resp.isSuccess());
        verifyNoInteractions(emailPublisher);
    }

    @Test
    void forgotPassword_onCooldown_doesNotSendEmail() {
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        req.setEmail("test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(otpService.isOnCooldown("reset", "test@example.com")).thenReturn(true);

        authService.forgotPassword(req);

        verifyNoInteractions(emailPublisher);
    }

    // ── verifyResetOtp ───────────────────────────────────────────────────────

    @Test
    void verifyResetOtp_valid_returnsResetToken() {
        OtpVerifyRequest req = new OtpVerifyRequest();
        req.setEmail("test@example.com");
        req.setOtp("112233");
        when(otpService.verify("reset", "test@example.com", "112233")).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(jwtUtil.generateResetToken(1)).thenReturn("reset-jwt");

        ApiResponse<String> resp = authService.verifyResetOtp(req);

        assertTrue(resp.isSuccess());
        assertEquals("reset-jwt", resp.getData());
    }

    @Test
    void verifyResetOtp_invalidOtp_throws() {
        OtpVerifyRequest req = new OtpVerifyRequest();
        req.setEmail("test@example.com");
        req.setOtp("000000");
        when(otpService.verify("reset", "test@example.com", "000000")).thenReturn(false);
        assertThrows(BadRequestException.class, () -> authService.verifyResetOtp(req));
    }

    // ── resetPassword ────────────────────────────────────────────────────────

    @Test
    void resetPassword_validToken_updatesPassword() {
        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setResetToken("valid-reset");
        req.setNewPassword("New@Pass1");
        when(jwtUtil.isValid("valid-reset")).thenReturn(true);
        io.jsonwebtoken.Claims claims = mock(io.jsonwebtoken.Claims.class);
        when(claims.getSubject()).thenReturn("1");
        when(claims.get("purpose")).thenReturn("PASSWORD_RESET");
        when(jwtUtil.parseToken("valid-reset")).thenReturn(claims);
        when(userRepository.findById(1)).thenReturn(Optional.of(testUser));
        when(passwordEncoder.encode("New@Pass1")).thenReturn("newhash");
        when(userRepository.save(any())).thenReturn(testUser);
        when(redis.opsForValue()).thenReturn(valueOps);

        ApiResponse<Void> resp = authService.resetPassword(req);

        assertTrue(resp.isSuccess());
        assertEquals("newhash", testUser.getPasswordHash());
    }

    @Test
    void resetPassword_invalidToken_throws() {
        ResetPasswordRequest req = new ResetPasswordRequest();
        req.setResetToken("bad");
        when(jwtUtil.isValid("bad")).thenReturn(false);
        assertThrows(UnauthorizedException.class, () -> authService.resetPassword(req));
    }

    // ── updateProfile ────────────────────────────────────────────────────────

    @Test
    void updateProfile_changesFullNameAndBio() {
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setFullName("New Name");
        req.setBio("My bio");
        when(userRepository.findById(1)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        UserProfileDto result = authService.updateProfile(1, req);

        assertEquals("New Name", result.getFullName());
        assertEquals("My bio", result.getBio());
    }

    @Test
    void updateProfile_usernameConflict_throws() {
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setUsername("taken");
        when(userRepository.findById(1)).thenReturn(Optional.of(testUser));
        when(userRepository.existsByUsername("taken")).thenReturn(true);
        assertThrows(DuplicateResourceException.class, () -> authService.updateProfile(1, req));
    }

    // ── changePassword ───────────────────────────────────────────────────────

    @Test
    void changePassword_success() {
        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setCurrentPassword("old");
        req.setNewPassword("New@Pass1");
        when(userRepository.findById(1)).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("old", "hashed")).thenReturn(true);
        when(passwordEncoder.encode("New@Pass1")).thenReturn("newhash");
        when(userRepository.save(any())).thenReturn(testUser);

        assertDoesNotThrow(() -> authService.changePassword(1, req));
        assertEquals("newhash", testUser.getPasswordHash());
    }

    @Test
    void changePassword_wrongCurrentPassword_throws() {
        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setCurrentPassword("wrong");
        req.setNewPassword("New@Pass1");
        when(userRepository.findById(1)).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);
        assertThrows(UnauthorizedException.class, () -> authService.changePassword(1, req));
    }

    @Test
    void changePassword_oauthUser_throws() {
        testUser.setProvider("GITHUB");
        ChangePasswordRequest req = new ChangePasswordRequest();
        when(userRepository.findById(1)).thenReturn(Optional.of(testUser));
        assertThrows(BadRequestException.class, () -> authService.changePassword(1, req));
    }

    // ── updateStatus ─────────────────────────────────────────────────────────

    @Test
    void updateStatus_validStatus_saves() {
        when(userRepository.findById(1)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any())).thenReturn(testUser);
        authService.updateStatus(1, "AWAY");
        assertEquals("AWAY", testUser.getStatus());
    }

    @Test
    void updateStatus_invalidStatus_throws() {
        assertThrows(BadRequestException.class, () -> authService.updateStatus(1, "SLEEPING"));
    }

    // ── admin: suspend / reactivate ──────────────────────────────────────────

    @Test
    void suspendUser_setsInactive() {
        when(userRepository.findById(1)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any())).thenReturn(testUser);
        User result = authService.suspendUser(1);
        assertFalse(result.isActive());
        assertEquals("SUSPENDED", result.getStatus());
    }

    @Test
    void reactivateUser_setsActive() {
        testUser.setActive(false);
        testUser.setStatus("SUSPENDED");
        when(userRepository.findById(1)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any())).thenReturn(testUser);
        User result = authService.reactivateUser(1);
        assertTrue(result.isActive());
        assertEquals("OFFLINE", result.getStatus());
    }

    // ── getUserById ──────────────────────────────────────────────────────────

    @Test
    void getUserById_notFound_throws() {
        when(userRepository.findById(999)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> authService.getUserById(999));
    }

    // ── Additional coverage for 70%+ threshold ──────────────────────────────

    @Test
    void oAuth2Callback_throws() {
        assertThrows(BadRequestException.class, () -> authService.oAuth2Callback("google", null));
    }

    @Test
    void verifyRegistrationOtp_e2eBypass_success() {
        OtpVerifyRequest req = new OtpVerifyRequest();
        req.setEmail("user@test.com");
        req.setOtp("000000");
        testUser.setEmailVerified(false);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(testUser));
        when(userRepository.save(any())).thenReturn(testUser);
        when(jwtUtil.generateAccessToken(any())).thenReturn("at");
        when(jwtUtil.generateRefreshToken(any())).thenReturn("rt");
        AuthResponse resp = authService.verifyRegistrationOtp(req);
        assertNotNull(resp.getAccessToken());
    }

    @Test
    void requestPhoneOtp_success() {
        PhoneOtpRequest req = new PhoneOtpRequest();
        req.setPhoneNumber("1234567890");
        when(otpService.isOnCooldown("phone", "1234567890")).thenReturn(false);
        when(otpService.generateAndStore("phone", "1234567890", 5)).thenReturn("1234");
        ApiResponse<Void> r = authService.requestPhoneOtp(req);
        assertTrue(r.isSuccess());
    }

    @Test
    void requestPhoneOtp_onCooldown_returnsRemaining() {
        PhoneOtpRequest req = new PhoneOtpRequest();
        req.setPhoneNumber("1234");
        when(otpService.isOnCooldown("phone", "1234")).thenReturn(true);
        when(otpService.getCooldownRemaining("phone", "1234")).thenReturn(10L);
        ApiResponse<Void> r = authService.requestPhoneOtp(req);
        assertFalse(r.isSuccess());
        assertEquals(10, r.getCooldownSeconds());
    }

    @Test
    void verifyPhoneOtp_success() {
        PhoneOtpVerifyRequest req = new PhoneOtpVerifyRequest();
        req.setPhoneNumber("123");
        req.setOtp("1");
        when(otpService.verify("phone", "123", "1")).thenReturn(true);
        when(userRepository.findAllByPhoneNumberOrderByUserIdDesc("123")).thenReturn(java.util.List.of(testUser));
        authService.verifyPhoneOtp(req);
        verify(userRepository).save(any());
    }

    @Test
    void loginAsGuest_createsTempAccount() {
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(jwtUtil.generateAccessToken(any())).thenReturn("tok");
        when(jwtUtil.generateRefreshToken(any())).thenReturn("rtok");
        AuthResponse r = authService.loginAsGuest();
        assertNotNull(r);
    }

    @Test
    void requestEmailLoginOtp_success() {
        EmailLoginOtpRequest req = new EmailLoginOtpRequest();
        req.setEmail("test@example.com");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(otpService.generateAndStore(any(), any(), anyInt())).thenReturn("1");
        ApiResponse<Void> r = authService.requestEmailLoginOtp(req);
        assertTrue(r.isSuccess());
    }

    @Test
    void loginWithEmailOtp_success() {
        OtpVerifyRequest req = new OtpVerifyRequest();
        req.setEmail("test@example.com");
        req.setOtp("1");
        when(otpService.verify("emaillogin", "test@example.com", "1")).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(jwtUtil.generateAccessToken(any())).thenReturn("ok");
        when(jwtUtil.generateRefreshToken(any())).thenReturn("rt");
        AuthResponse r = authService.loginWithEmailOtp(req);
        assertEquals("ok", r.getAccessToken());
    }

    @Test
    void requestPhoneLoginOtp_success() {
        PhoneOtpRequest req = new PhoneOtpRequest();
        req.setPhoneNumber("123");
        testUser.setPhoneVerified(true);
        when(userRepository.findAllByPhoneNumberOrderByUserIdDesc("123")).thenReturn(java.util.List.of(testUser));
        when(otpService.generateAndStore(any(), any(), anyInt())).thenReturn("1");
        ApiResponse<Void> r = authService.requestPhoneLoginOtp(req);
        assertTrue(r.isSuccess());
    }

    @Test
    void requestPhoneLoginOtp_unverifiedPhone_stillSendsOtp() {
        PhoneOtpRequest req = new PhoneOtpRequest();
        req.setPhoneNumber("123");
        testUser.setPhoneVerified(false);
        when(userRepository.findAllByPhoneNumberOrderByUserIdDesc("123")).thenReturn(java.util.List.of(testUser));
        when(otpService.generateAndStore("phonelogin", "123", 5)).thenReturn("123456");

        ApiResponse<Void> r = authService.requestPhoneLoginOtp(req);

        assertTrue(r.isSuccess());
        verify(emailPublisher).sendSmsOtp("123", "123456");
    }

    @Test
    void loginWithPhoneOtp_success() {
        PhoneOtpVerifyRequest req = new PhoneOtpVerifyRequest();
        req.setPhoneNumber("123");
        req.setOtp("1");
        when(otpService.verify("phonelogin", "123", "1")).thenReturn(true);
        when(userRepository.findAllByPhoneNumberOrderByUserIdDesc("123")).thenReturn(java.util.List.of(testUser));
        when(jwtUtil.generateAccessToken(any())).thenReturn("tok");
        when(jwtUtil.generateRefreshToken(any())).thenReturn("rtok");
        AuthResponse r = authService.loginWithPhoneOtp(req);
        assertNotNull(r);
    }

    @Test
    void loginWithPhoneOtp_marksPhoneVerified() {
        PhoneOtpVerifyRequest req = new PhoneOtpVerifyRequest();
        req.setPhoneNumber("123");
        req.setOtp("1");
        testUser.setPhoneVerified(false);
        when(otpService.verify("phonelogin", "123", "1")).thenReturn(true);
        when(userRepository.findAllByPhoneNumberOrderByUserIdDesc("123")).thenReturn(java.util.List.of(testUser));
        when(jwtUtil.generateAccessToken(any())).thenReturn("tok");
        when(jwtUtil.generateRefreshToken(any())).thenReturn("rtok");

        authService.loginWithPhoneOtp(req);

        assertTrue(testUser.isPhoneVerified());
        verify(userRepository).save(testUser);
    }

    @Test
    void getPublicProfile_cacheHit_returnsCached() {
        UserProfileDto dto = new UserProfileDto();
        when(profileCache.getCachedProfile(1)).thenReturn(dto);
        UserProfileDto r = authService.getPublicProfile(1);
        assertEquals(dto, r);
        verify(userRepository, never()).findById(anyInt());
    }

    @Test
    void getPublicProfile_cacheMiss_fetchesAndCaches() {
        when(profileCache.getCachedProfile(1)).thenReturn(null);
        when(userRepository.findById(1)).thenReturn(Optional.of(testUser));
        UserProfileDto r = authService.getPublicProfile(1);
        verify(profileCache).cacheProfile(eq(1), any());
        assertEquals("testuser", r.getUsername());
    }

    @Test
    void updateLastSeen_setsOffline() {
        when(userRepository.findById(1)).thenReturn(Optional.of(testUser));
        authService.updateLastSeen(1);
        verify(userRepository).save(any());
        assertEquals("OFFLINE", testUser.getStatus());
        assertNotNull(testUser.getLastSeenAt());
    }

    @Test
    void deleteUser_publishesKafkaEvent() {
        authService.deleteUser(1);
        verify(userRepository).deleteById(1);
        verify(kafkaTemplate).send("auth.user.deleted", "1");
    }

    @Test
    void getAllUsers_searchUsers_getUsersByIds() {
        when(userRepository.findAll()).thenReturn(java.util.List.of(testUser));
        assertFalse(authService.getAllUsers().isEmpty());

        when(userRepository.searchUsers("test")).thenReturn(java.util.List.of(testUser));
        assertFalse(authService.searchUsers("test").isEmpty());

        when(userRepository.findByUserIdIn(any())).thenReturn(java.util.List.of(testUser));
        assertFalse(authService.getUsersByIds(java.util.List.of(1)).isEmpty());
    }
}
