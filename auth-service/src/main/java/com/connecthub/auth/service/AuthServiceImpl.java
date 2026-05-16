package com.connecthub.auth.service;

import com.connecthub.auth.config.JwtUtil;
import com.connecthub.auth.dto.*;
import com.connecthub.auth.entity.User;
import com.connecthub.auth.exception.*;
import com.connecthub.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * AuthServiceImpl — Core Authentication Business Logic
 *
 * PURPOSE:
 *   This is the main service class that implements all authentication flows
 *   in the auth-service. It handles registration, email/phone OTP flows,
 *   password login, guest login, OAuth2 callbacks, session management,
 *   forgot/reset password, and user profile management.
 *
 *   It is annotated with @Transactional at the class level, meaning every
 *   public method runs inside a database transaction by default. Methods that
 *   only read data override this with @Transactional(readOnly = true) for
 *   a small performance gain (MySQL can use a read-only optimized execution path).
 *
 * DEPENDENCIES:
 *   - UserRepository      — JPA repository for reading/writing User entities to MySQL
 *   - PasswordEncoder     — BCrypt encoder for hashing and verifying passwords
 *   - JwtUtil             — generates and validates JWT tokens
 *   - OtpService          — Redis-backed OTP generation, storage, verification
 *   - StringRedisTemplate — direct Redis access for token blacklist
 *   - EmailEventPublisher — publishes OTP/welcome events to Redis pub/sub
 *   - UserProfileCacheService — Redis cache for user profile data
 *
 * KEY DESIGN DECISIONS:
 *
 *   Token Blacklist:
 *     On logout, the access token is stored in Redis with a 24-hour TTL
 *     under the key "token:blacklist:{token}". The validateToken() endpoint
 *     checks this blacklist before confirming a token is valid. This means
 *     even a non-expired token becomes invalid immediately after logout.
 *
 *   Forgot Password — Security Non-Disclosure:
 *     forgotPassword() always returns a generic "if an account exists…" message
 *     even when the email isn't registered. This prevents email enumeration
 *     (an attacker testing "did this email register here?" by observing the response).
 *
 *   Password Reset Invalidation:
 *     After resetPassword(), a Redis key "user:invalidated:{userId}" is set.
 *     This allows the API gateway or other services to detect that old tokens
 *     issued before the password change should no longer be trusted.
 *
 *   Guest Login:
 *     Creates a real user account in the database with role=GUEST, using a
 *     random UUID-based username and a private email domain (@guest.connecthub.local).
 *     The password is a random UUID hash so no one can log in as a guest via password.
 *     Guest accounts are not cleaned up automatically — a scheduled job could prune them.
 *
 *   OAuth2:
 *     The oAuth2Callback() method throws an error because OAuth2 login is handled
 *     entirely by Spring Security (see SecurityConfig + OAuth2SuccessHandler).
 *     This method exists only to satisfy the AuthService interface definition.
 *
 *   E2E Test Bypass:
 *     verifyRegistrationOtp() has a special case: if the email ends with "@test.com"
 *     and the OTP is "000000", verification succeeds without checking Redis.
 *     This allows automated end-to-end tests to run without needing real email delivery.
 */
@SuppressWarnings("null")
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final OtpService otpService;
    private final StringRedisTemplate redis;
    private final EmailEventPublisher emailPublisher;
    private final UserProfileCacheService profileCache;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /** Redis key prefix for the token blacklist — stores invalidated access tokens */
    private static final String TOKEN_BLACKLIST = "token:blacklist:";

    // =========================================================================
    // REGISTRATION
    // =========================================================================

    /**
     * register — creates a new unverified local user account and emails a registration OTP.
     * Validates that neither the email nor username is already taken before saving.
     */
    @Override
    public ApiResponse<String> register(RegisterRequest req) {
        String email = normalizeEmail(req.getEmail());
        Optional<User> existingUser = userRepository.findByEmail(email);
        if (existingUser.isPresent()) {
            if (existingUser.get().isEmailVerified()) {
                throw new DuplicateResourceException("Email already registered");
            }
            return sendRegistrationOtp(email, existingUser.get().getUsername(), true);
        }
        if (userRepository.existsByUsername(req.getUsername()))
            throw new DuplicateResourceException("Username already taken");
        if (req.getPhoneNumber() != null && !req.getPhoneNumber().isBlank()
                && userRepository.existsByPhoneNumber(req.getPhoneNumber()))
            throw new DuplicateResourceException("Phone number already registered");

        User user = User.builder()
                .username(req.getUsername()).email(email)
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .fullName(req.getFullName()).phoneNumber(req.getPhoneNumber())
                .emailVerified(false).phoneVerified(false)
                .build();
        userRepository.save(user);

        return sendRegistrationOtp(email, req.getUsername(), false);
    }

    /**
     * verifyRegistrationOtp — verifies the email OTP sent after registration.
     * On success, marks the user's email as verified and returns full JWT tokens
     * so the user is immediately logged in without a separate login step.
     * The E2E test bypass (email ends "@test.com" + OTP "000000") allows automated
     * tests to verify accounts without real email delivery.
     */
    @Override
    public AuthResponse verifyRegistrationOtp(OtpVerifyRequest req) {
        String email = normalizeEmail(req.getEmail());
        boolean isE2eTestUser = email != null && email.endsWith("@test.com") && "000000".equals(req.getOtp());
        if (!isE2eTestUser && !otpService.verify("register", email, req.getOtp()))
            throw new BadRequestException("Invalid or expired OTP. Max 5 attempts allowed.");

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setEmailVerified(true);
        userRepository.save(user);
        log.info("Email verified for user: {}", user.getUsername());
        emailPublisher.sendWelcomeEmail(user.getEmail(), user.getUsername());
        return buildAuthResponse(user);
    }

    /**
     * resendRegistrationOtp — re-sends the registration verification OTP.
     * Checks the cooldown before issuing a new OTP — if the cooldown has not
     * expired yet, returns the remaining wait time instead of sending again.
     * Prevents a user who already verified from requesting OTPs (throws if already verified).
     */
    @Override
    public ApiResponse<Void> resendRegistrationOtp(String email) {
        email = normalizeEmail(email);
        if (otpService.isOnCooldown("register", email)) {
            long remaining = otpService.getCooldownRemaining("register", email);
            return ApiResponse.<Void>builder().success(false)
                    .message("Please wait before requesting another OTP")
                    .cooldownSeconds((int) remaining).build();
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.isEmailVerified()) throw new BadRequestException("Email already verified");

        String otp = otpService.generateAndStore("register", email, 5);
        otpService.setCooldown("register", email, 60);
        emailPublisher.sendOtpEmail(email, otp, "registration");
        return ApiResponse.ok("OTP resent to your email");
    }

    // =========================================================================
    // PHONE OTP
    // =========================================================================

    /**
     * requestPhoneOtp — sends an OTP to a phone number for verification.
     * Used both for registering a phone number and for phone-based login.
     * The OTP is published to the Redis channel where notification-service
     * will pick it up and forward it to an SMS gateway.
     */
    @Override
    public ApiResponse<Void> requestPhoneOtp(PhoneOtpRequest request) {
        String phone = request.getPhoneNumber();
        if (otpService.isOnCooldown("phone", phone)) {
            long remaining = otpService.getCooldownRemaining("phone", phone);
            return ApiResponse.<Void>builder().success(false)
                    .message("Please wait before requesting another OTP")
                    .cooldownSeconds((int) remaining).build();
        }
        String otp = otpService.generateAndStore("phone", phone, 5);
        otpService.setCooldown("phone", phone, 45);
        emailPublisher.sendSmsOtp(phone, otp);
        return ApiResponse.ok("OTP sent to your phone number");
    }

    /**
     * verifyPhoneOtp — verifies a phone OTP and marks the phone as verified on the
     * user's account if an account with this phone number exists.
     */
    @Override
    public ApiResponse<Void> verifyPhoneOtp(PhoneOtpVerifyRequest request) {
        if (!otpService.verify("phone", request.getPhoneNumber(), request.getOtp()))
            throw new BadRequestException("Invalid or expired phone OTP");

        findUserByPhoneNumber(request.getPhoneNumber()).ifPresent(user -> {
            user.setPhoneVerified(true);
            userRepository.save(user);
            log.info("Phone verified for user: {}", user.getUsername());
        });

        return ApiResponse.ok("Phone number verified successfully");
    }

    // =========================================================================
    // LOGIN — EMAIL/USERNAME + PASSWORD
    // =========================================================================

    /**
     * login — authenticates a user via username-or-email + password.
     * The identifier is tried first as an email, then as a username if no email
     * match is found. This lets users log in with either credential type.
     * Additional checks: account must be active, email must be verified, and
     * the account must be a LOCAL (not OAuth) account.
     */
    @Override
    public AuthResponse login(LoginRequest req) {
        User user = null;
        if (req.getEmail() != null && !req.getEmail().isBlank()) {
            String email = normalizeEmail(req.getEmail());
            user = userRepository.findByEmail(email).orElse(null);
            if (user == null) {
                user = userRepository.findByUsername(req.getEmail()).orElse(null);
            }
        }
        if (user == null && req.getUsername() != null && !req.getUsername().isBlank()) {
            user = userRepository.findByUsername(req.getUsername()).orElse(null);
        }
        if (user == null) throw new UnauthorizedException("Invalid credentials");

        if (!user.isActive()) throw new UnauthorizedException("Account is suspended");
        if (!"LOCAL".equals(user.getProvider()))
            throw new UnauthorizedException("Please use " + user.getProvider() + " to sign in");
        if (!user.isEmailVerified())
            throw new UnauthorizedException("Email not verified. Please verify your email first.");

        if (req.getPassword() == null || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash()))
            throw new UnauthorizedException("Invalid credentials");

        log.info("User logged in (password): {}", user.getUsername());
        return buildAuthResponse(user);
    }

    // =========================================================================
    // GUEST LOGIN
    // =========================================================================

    /**
     * loginAsGuest — creates a temporary guest account and returns JWT tokens.
     * The guest has role=GUEST so the gateway can apply stricter rate limits.
     * A random UUID fragment is used for the username and email to ensure uniqueness.
     * The password is a random UUID hash — no one can log in as this guest via password.
     */
    @Override
    public AuthResponse loginAsGuest() {
        String uuid = java.util.UUID.randomUUID().toString().substring(0, 8);
        String guestUsername = "guest_" + uuid;
        User user = User.builder()
                .username(guestUsername)
                .email(guestUsername + "@guest.connecthub.local")
                .passwordHash(passwordEncoder.encode(java.util.UUID.randomUUID().toString()))
                .fullName("Guest " + uuid)
                .emailVerified(true)
                .phoneVerified(true)
                .role("GUEST")
                .subscriptionTier("FREE")
                .status("ONLINE")
                .provider("LOCAL")
                .active(true)
                .build();
        userRepository.save(user);
        log.info("Temporary Guest user created: {}", guestUsername);
        return buildAuthResponse(user);
    }

    // =========================================================================
    // LOGIN — EMAIL + OTP
    // =========================================================================

    /**
     * requestEmailLoginOtp — sends a login OTP to a verified email address.
     * Requires the user to exist, be active, and have a verified email before
     * issuing the OTP. Cooldown prevents repeated requests within 45 seconds.
     */
    @Override
    public ApiResponse<Void> requestEmailLoginOtp(EmailLoginOtpRequest request) {
        String email = normalizeEmail(request.getEmail());
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("No account found with this email"));
        if (!user.isActive()) throw new UnauthorizedException("Account is suspended");
        if (!user.isEmailVerified())
            throw new UnauthorizedException("Email not verified. Please verify your email first.");

        if (otpService.isOnCooldown("emaillogin", email)) {
            long remaining = otpService.getCooldownRemaining("emaillogin", email);
            return ApiResponse.<Void>builder().success(false)
                    .message("Please wait before requesting another OTP")
                    .cooldownSeconds((int) remaining).build();
        }

        String otp = otpService.generateAndStore("emaillogin", email, 5);
        otpService.setCooldown("emaillogin", email, 45);
        emailPublisher.sendOtpEmail(email, otp, "login");
        return ApiResponse.ok("Login OTP sent to your email");
    }

    /**
     * loginWithEmailOtp — verifies the login OTP and returns JWT tokens on success.
     */
    @Override
    public AuthResponse loginWithEmailOtp(OtpVerifyRequest request) {
        String email = normalizeEmail(request.getEmail());
        if (!otpService.verify("emaillogin", email, request.getOtp()))
            throw new BadRequestException("Invalid or expired OTP");

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!user.isActive()) throw new UnauthorizedException("Account is suspended");

        log.info("User logged in (email OTP): {}", user.getUsername());
        return buildAuthResponse(user);
    }

    // =========================================================================
    // LOGIN — PHONE + OTP
    // =========================================================================

    /**
     * requestPhoneLoginOtp — sends a login OTP via SMS to a registered phone number.
     * A successful phone login OTP also verifies the phone, so users who added a
     * phone number during registration are not blocked by a separate verification step.
     */
    @Override
    public ApiResponse<Void> requestPhoneLoginOtp(PhoneOtpRequest request) {
        String phone = request.getPhoneNumber();
        User user = findPhoneLoginUser(phone)
                .orElseThrow(() -> new ResourceNotFoundException("No account found with this phone number"));
        if (!user.isActive()) throw new UnauthorizedException("Account is suspended");

        if (otpService.isOnCooldown("phonelogin", phone)) {
            long remaining = otpService.getCooldownRemaining("phonelogin", phone);
            return ApiResponse.<Void>builder().success(false)
                    .message("Please wait before requesting another OTP")
                    .cooldownSeconds((int) remaining).build();
        }

        String otp = otpService.generateAndStore("phonelogin", phone, 5);
        otpService.setCooldown("phonelogin", phone, 45);
        emailPublisher.sendSmsOtp(phone, otp);
        return ApiResponse.ok("Login OTP sent to your phone");
    }

    /**
     * loginWithPhoneOtp — verifies the phone login OTP and returns JWT tokens.
     */
    @Override
    public AuthResponse loginWithPhoneOtp(PhoneOtpVerifyRequest request) {
        if (!otpService.verify("phonelogin", request.getPhoneNumber(), request.getOtp()))
            throw new BadRequestException("Invalid or expired OTP");

        User user = findPhoneLoginUser(request.getPhoneNumber())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!user.isActive()) throw new UnauthorizedException("Account is suspended");
        if (!user.isPhoneVerified()) {
            user.setPhoneVerified(true);
            userRepository.save(user);
            log.info("Phone verified during OTP login for user: {}", user.getUsername());
        }

        log.info("User logged in (phone OTP): {}", user.getUsername());
        return buildAuthResponse(user);
    }

    // =========================================================================
    // SESSION MANAGEMENT
    // =========================================================================

    /**
     * logout — blacklists the access token in Redis so it cannot be used again.
     * The token is stored with a 24-hour TTL (matching the max access token lifetime)
     * so the key automatically expires and never accumulates indefinitely.
     * The "Bearer " prefix is stripped if present since we store only the raw token.
     */
    @Override
    public void logout(String token) {
        if (token != null && token.startsWith("Bearer ")) token = token.substring(7);
        redis.opsForValue().set(TOKEN_BLACKLIST + token, "1", 24, TimeUnit.HOURS);
    }

    /**
     * validateToken — checks whether a token is valid and not blacklisted.
     * Used by the /auth/validate endpoint which the gateway can call to verify tokens
     * without needing the JWT secret (though the gateway currently validates locally).
     */
    @Override
    public boolean validateToken(String token) {
        if (Boolean.TRUE.equals(redis.hasKey(TOKEN_BLACKLIST + token))) return false;
        return jwtUtil.isValid(token);
    }

    /**
     * refreshToken — issues a new access token in exchange for a valid refresh token.
     * The refresh token is validated (signature + expiry) by jwtUtil.isValid().
     * A fresh access token is generated from the current database state of the user,
     * so any role or subscription tier changes are reflected in the new token.
     */
    @Override
    public AuthResponse refreshToken(String refreshToken) {
        if (!jwtUtil.isValid(refreshToken)) throw new UnauthorizedException("Invalid refresh token");
        User user = getUserById(jwtUtil.getUserId(refreshToken));
        return buildAuthResponse(user);
    }

    // =========================================================================
    // FORGOT PASSWORD
    // =========================================================================

    /**
     * forgotPassword — initiates a password reset by sending an OTP to the email.
     * Always returns a generic success message even if the email isn't registered,
     * to prevent email enumeration attacks (attackers probing which emails exist).
     * Only LOCAL accounts can reset passwords via OTP — OAuth users don't have a
     * password managed by ConnectHub.
     */
    @Override
    public ApiResponse<Void> forgotPassword(ForgotPasswordRequest req) {
        String email = normalizeEmail(req.getEmail());
        userRepository.findByEmail(email).ifPresent(user -> {
            if (!"LOCAL".equals(user.getProvider())) return;
            if (!otpService.isOnCooldown("reset", email)) {
                String otp = otpService.generateAndStore("reset", email, 10);
                otpService.setCooldown("reset", email, 60);
                emailPublisher.sendOtpEmail(email, otp, "password_reset");
            }
        });
        return ApiResponse.ok("If an account with this email exists, we have sent a reset code.");
    }

    /**
     * verifyResetOtp — verifies the password reset OTP and returns a short-lived
     * resetToken if the OTP is correct. The resetToken is a JWT with "purpose":
     * "PASSWORD_RESET" that must be passed to resetPassword() to authorize the change.
     * Using a JWT avoids needing to store server-side state for reset sessions.
     */
    @Override
    public ApiResponse<String> verifyResetOtp(OtpVerifyRequest req) {
        String email = normalizeEmail(req.getEmail());
        if (!otpService.verify("reset", email, req.getOtp()))
            throw new BadRequestException("Invalid or expired OTP");
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        String resetToken = jwtUtil.generateResetToken(user.getUserId());
        return ApiResponse.ok("OTP verified. Use the reset token to set a new password.", resetToken);
    }

    /**
     * resetPassword — sets a new password using the reset token from verifyResetOtp().
     * Validates the token's signature, expiry, and "purpose" claim before applying
     * the change. After resetting, invalidates all existing sessions by writing a
     * "user:invalidated:{userId}" key to Redis, signaling that old tokens are stale.
     */
    @Override
    public ApiResponse<Void> resetPassword(ResetPasswordRequest req) {
        if (!jwtUtil.isValid(req.getResetToken())) throw new UnauthorizedException("Invalid or expired reset token");
        var claims = jwtUtil.parseToken(req.getResetToken());
        if (!"PASSWORD_RESET".equals(claims.get("purpose")))
            throw new UnauthorizedException("Invalid token purpose");

        int userId = Integer.parseInt(claims.getSubject());
        User user = getUserById(userId);
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(user);

        /*
         * Invalidation marker — any system that needs to verify "is this token
         * still valid post password-reset?" can check this key. The 7-day TTL
         * ensures it covers the lifetime of any refresh tokens that might exist.
         */
        redis.opsForValue().set("user:invalidated:" + userId, String.valueOf(System.currentTimeMillis()), 7, TimeUnit.DAYS);
        log.info("Password reset completed for user: {}", user.getUsername());
        return ApiResponse.ok("Password reset successful. Please login with your new password.");
    }

    // =========================================================================
    // OAUTH2
    // =========================================================================

    /**
     * oAuth2Callback — not used. OAuth2 login is handled entirely by Spring Security
     * (configured in SecurityConfig with OAuth2AuthenticationSuccessHandler).
     * This method exists only to satisfy the AuthService interface.
     */
    @Override
    public AuthResponse oAuth2Callback(String provider, OAuth2CallbackRequest req) {
        throw new BadRequestException("Use /oauth2/authorization/" + provider + " for OAuth2 login");
    }

    // =========================================================================
    // USER MANAGEMENT
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public User getUserById(int userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    /**
     * getPublicProfile — returns a user's public profile, using Redis cache.
     * On cache hit: returns the cached DTO without touching the database.
     * On cache miss: loads from MySQL, populates the cache, returns the DTO.
     */
    @Override
    public UserProfileDto getPublicProfile(int userId) {
        UserProfileDto cached = profileCache.getCachedProfile(userId);
        if (cached != null) {
            log.debug("Cache HIT user:profile:{}", userId);
            return cached;
        }
        log.debug("Cache MISS user:profile:{} — loading from DB", userId);
        UserProfileDto profile = toProfileDto(getUserById(userId));
        profileCache.cacheProfile(userId, profile);
        return profile;
    }

    /**
     * updateProfile — updates editable profile fields and invalidates the Redis cache.
     * Username uniqueness is re-checked only if it actually changed.
     */
    @Override
    public UserProfileDto updateProfile(int userId, UpdateProfileRequest req) {
        User user = getUserById(userId);
        if (req.getFullName() != null) user.setFullName(req.getFullName());
        if (req.getUsername() != null && !user.getUsername().equals(req.getUsername())) {
            if (userRepository.existsByUsername(req.getUsername()))
                throw new DuplicateResourceException("Username already taken");
            user.setUsername(req.getUsername());
        }
        if (req.getAvatarUrl() != null) user.setAvatarUrl(req.getAvatarUrl());
        if (req.getBio() != null) user.setBio(req.getBio());
        if (req.getPhoneNumber() != null && !req.getPhoneNumber().equals(user.getPhoneNumber())) {
            if (!req.getPhoneNumber().isBlank()
                    && userRepository.existsByPhoneNumberAndUserIdNot(req.getPhoneNumber(), userId)) {
                throw new DuplicateResourceException("Phone number already registered");
            }
            user.setPhoneNumber(req.getPhoneNumber().isBlank() ? null : req.getPhoneNumber());
            user.setPhoneVerified(false);
        }
        UserProfileDto updated = toProfileDto(userRepository.save(user));
        profileCache.evict(userId);
        return updated;
    }

    /**
     * changePassword — changes a user's password after verifying the current one.
     * OAuth users cannot change passwords because ConnectHub doesn't manage their credentials.
     */
    @Override
    public void changePassword(int userId, ChangePasswordRequest req) {
        User user = getUserById(userId);
        if (!"LOCAL".equals(user.getProvider())) throw new BadRequestException("OAuth users cannot change password");
        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPasswordHash()))
            throw new UnauthorizedException("Current password is incorrect");
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserProfileDto> searchUsers(String query) {
        return userRepository.searchUsers(query).stream().map(this::toProfileDto).toList();
    }

    /**
     * updateStatus — sets the user's online/away/DND/invisible status.
     * Validates against the allowed set before saving, and evicts the profile
     * cache so other services see the status change on next fetch.
     */
    @Override
    public void updateStatus(int userId, String status) {
        if (!List.of("ONLINE", "AWAY", "DND", "INVISIBLE").contains(status))
            throw new BadRequestException("Invalid status: " + status);
        User user = getUserById(userId);
        user.setStatus(status);
        userRepository.save(user);
        profileCache.evict(userId);
    }

    /**
     * updateLastSeen — called when a user disconnects their WebSocket.
     * Sets status to OFFLINE and records the current timestamp as lastSeenAt.
     */
    @Override
    public void updateLastSeen(int userId) {
        User user = getUserById(userId);
        user.setLastSeenAt(LocalDateTime.now());
        user.setStatus("OFFLINE");
        userRepository.save(user);
    }

    @Override
    public User suspendUser(int userId) {
        User u = getUserById(userId);
        u.setActive(false);
        u.setStatus("SUSPENDED");
        User saved = userRepository.save(u);
        profileCache.evict(userId);
        return saved;
    }

    @Override
    public User reactivateUser(int userId) {
        User u = getUserById(userId);
        u.setActive(true);
        if ("SUSPENDED".equalsIgnoreCase(u.getStatus())) {
            u.setStatus("OFFLINE");
        }
        User saved = userRepository.save(u);
        profileCache.evict(userId);
        return saved;
    }

    @Override
    public void deleteUser(int userId) {
        userRepository.deleteById(userId);
        log.info("Publishing USER_DELETED event for userId: {}", userId);
        kafkaTemplate.send("auth.user.deleted", String.valueOf(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserProfileDto> getUsersByIds(List<Integer> ids) {
        return userRepository.findByUserIdIn(ids).stream().map(this::toProfileDto).toList();
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    /**
     * toProfileDto — converts a User entity to the public-facing UserProfileDto.
     * Defaults subscriptionTier to "FREE" if the entity field is null.
     */
    private UserProfileDto toProfileDto(User u) {
        return UserProfileDto.builder()
                .userId(u.getUserId()).username(u.getUsername()).email(u.getEmail())
                .fullName(u.getFullName()).phoneNumber(u.getPhoneNumber())
                .avatarUrl(u.getAvatarUrl()).bio(u.getBio()).status(u.getStatus())
                .role(u.getRole())
                .subscriptionTier(u.getSubscriptionTier() != null ? u.getSubscriptionTier() : "FREE")
                .emailVerified(u.isEmailVerified())
                .phoneVerified(u.isPhoneVerified()).lastSeenAt(u.getLastSeenAt())
                .createdAt(u.getCreatedAt()).build();
    }

    /**
     * buildAuthResponse — constructs the standard login response containing
     * both tokens, token metadata (type, expiry in seconds), and a slim user DTO.
     * Called by every successful login/verify method to ensure a consistent response.
     */
    private AuthResponse buildAuthResponse(User user) {
        return AuthResponse.builder()
                .accessToken(jwtUtil.generateAccessToken(user))
                .refreshToken(jwtUtil.generateRefreshToken(user))
                .tokenType("Bearer")
                .expiresIn(jwtUtil.getAccessExpiry() / 1000)
                .user(AuthResponse.UserDto.builder()
                        .userId(user.getUserId()).username(user.getUsername()).email(user.getEmail())
                        .fullName(user.getFullName()).avatarUrl(user.getAvatarUrl())
                        .phoneNumber(user.getPhoneNumber())
                        .status(user.getStatus()).role(user.getRole())
                        .subscriptionTier(user.getSubscriptionTier() != null ? user.getSubscriptionTier() : "FREE")
                        .build())
                .build();
    }

    private Optional<User> findUserByPhoneNumber(String phoneNumber) {
        List<User> users = userRepository.findAllByPhoneNumberOrderByUserIdDesc(phoneNumber);
        if (users.size() > 1) {
            log.warn("Multiple users share phone number {}; using most recent userId={}",
                    phoneNumber, users.get(0).getUserId());
        }
        return users.stream().findFirst();
    }

    private Optional<User> findPhoneLoginUser(String phoneNumber) {
        List<User> users = userRepository.findAllByPhoneNumberOrderByUserIdDesc(phoneNumber);
        if (users.size() > 1) {
            log.warn("Multiple users share phone number {}; preferring most recent verified account",
                    phoneNumber);
        }
        return users.stream()
                .filter(User::isPhoneVerified)
                .findFirst()
                .or(() -> users.stream().findFirst());
    }

    private ApiResponse<String> sendRegistrationOtp(String email, String username, boolean existingUnverifiedUser) {
        if (otpService.isOnCooldown("register", email)) {
            long remaining = otpService.getCooldownRemaining("register", email);
            return ApiResponse.<String>builder()
                    .success(true)
                    .message("A verification code was already sent. Please check your email.")
                    .data(email)
                    .cooldownSeconds((int) remaining)
                    .build();
        }

        String otp = otpService.generateAndStore("register", email, 5);
        otpService.setCooldown("register", email, 60);
        emailPublisher.sendOtpEmail(email, otp, "registration");

        if (existingUnverifiedUser) {
            log.info("Verification OTP resent for existing unverified user: {}", username);
            return ApiResponse.ok("Verification code resent. Please verify your email.", email);
        }

        log.info("User registered successfully; verification OTP sent: {}", username);
        return ApiResponse.ok("Registration successful. Please verify your email with the OTP we sent.", email);
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
