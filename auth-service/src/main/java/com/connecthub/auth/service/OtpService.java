package com.connecthub.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * OtpService — One-Time Password Generation, Storage, and Verification via Redis
 *
 * PURPOSE:
 *   Manages the full lifecycle of OTPs (one-time passwords) used across different
 *   authentication flows: email registration verification, email login, phone OTP
 *   login, and password reset. All OTPs are stored in Redis with a TTL so they
 *   expire automatically without needing a scheduled cleanup job.
 *
 * HOW OTPs ARE STORED (Redis key structure):
 *   - OTP value:      "otp:{purpose}:{identifier}"    — the 6-digit code itself
 *   - Cooldown flag:  "otp:cooldown:{purpose}:{identifier}" — prevents rapid resends
 *   - Attempt count:  "otp:attempts:{purpose}:{identifier}" — tracks failed attempts
 *
 *   The "purpose" separates different OTP flows so they don't interfere with each other.
 *   Examples: "register", "emaillogin", "phonelogin", "reset", "phone".
 *   The "identifier" is an email address or phone number.
 *
 * SECURITY FEATURES:
 *   1. SecureRandom for generation — uses a cryptographically strong random number
 *      generator (not Math.random) so the OTP cannot be predicted.
 *   2. Attempt limiting (MAX_ATTEMPTS = 5) — after 5 failed verifications, the
 *      OTP is blocked even if it has not expired. This prevents brute-force attacks
 *      where an attacker tries 000000 through 999999 systematically.
 *   3. Cooldown period — setCooldown() stores a flag in Redis that prevents the
 *      user from requesting a new OTP too quickly, protecting against OTP spam
 *      (e.g., someone flooding another person's email/phone with OTP codes).
 *   4. One-time use — verify() deletes the OTP from Redis on success, so the same
 *      code cannot be used a second time even if it hasn't expired.
 *   5. Attempt counter reset on new OTP — generateAndStore() deletes the old
 *      attempts counter so a fresh OTP always gets a full 5-attempt budget.
 *
 * EXAMPLE FLOW (email registration):
 *   1. generateAndStore("register", email, 5) → stores code for 5 minutes
 *   2. setCooldown("register", email, 60)     → blocks resend for 60 seconds
 *   3. User enters code → verify("register", email, code)
 *      - Increments attempt counter
 *      - Compares submitted code to stored code
 *      - On match: deletes OTP and attempts counter, returns true
 *      - On mismatch: returns false (counter stays, OTP stays until TTL)
 *   4. After MAX_ATTEMPTS failures: always returns false regardless of code
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {

    private final StringRedisTemplate redis;

    /** SecureRandom is thread-safe and cryptographically strong — used for all OTP generation */
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String OTP_PREFIX      = "otp:";
    private static final String COOLDOWN_PREFIX = "otp:cooldown:";
    private static final String ATTEMPTS_PREFIX = "otp:attempts:";
    private static final int    MAX_ATTEMPTS    = 5;

    /**
     * generateAndStore — generates a 6-digit OTP, stores it in Redis with a TTL,
     * and resets the failed-attempts counter for this purpose/identifier combination.
     *
     * The OTP is formatted with %06d to ensure it is always exactly 6 digits
     * (e.g., the number 42 becomes "000042"), which matches the fixed-length
     * input boxes in the frontend.
     *
     * @param purpose    identifies the flow: "register", "emaillogin", "reset", etc.
     * @param email      the email or phone number that will receive the code
     * @param ttlMinutes how long (in minutes) the OTP is valid before expiring
     * @return the generated OTP string (returned so the caller can pass it to the email/SMS sender)
     */
    public String generateAndStore(String purpose, String email, int ttlMinutes) {
        email = normalizeIdentifier(email);
        String otp = String.format("%06d", RANDOM.nextInt(999999));
        String key = OTP_PREFIX + purpose + ":" + email;
        redis.opsForValue().set(key, otp, ttlMinutes, TimeUnit.MINUTES);

        /*
         * Reset the attempt counter so a freshly issued OTP always gets
         * a full 5 attempts, even if the previous OTP was exhausted.
         */
        redis.delete(ATTEMPTS_PREFIX + purpose + ":" + email);
        log.info("OTP generated for {}:{} (ttl={}min)", purpose, maskEmail(email), ttlMinutes);
        return otp;
    }

    /**
     * verify — checks a submitted OTP against the stored value.
     * Increments the failed-attempt counter before comparing so that even
     * a correct guess is counted as an attempt. This prevents an attacker
     * from trying 4 wrong codes, then racing with another process to get a
     * fresh attempt budget.
     *
     * On successful match: deletes both the OTP and the attempts counter from Redis.
     * On failure or exceeded attempts: returns false without deleting the OTP.
     *
     * @param purpose the flow identifier used when generating the OTP
     * @param email   the email or phone number the OTP was sent to
     * @param otp     the code submitted by the user
     * @return true if the code matches and hasn't been used or exceeded attempts
     */
    public boolean verify(String purpose, String email, String otp) {
        email = normalizeIdentifier(email);
        String attemptsKey = ATTEMPTS_PREFIX + purpose + ":" + email;
        String countStr = redis.opsForValue().get(attemptsKey);
        int attempts = countStr != null ? Integer.parseInt(countStr) : 0;

        if (attempts >= MAX_ATTEMPTS) {
            log.warn("OTP max attempts exceeded for {}:{}", purpose, maskEmail(email));
            return false;
        }

        /*
         * Increment BEFORE checking so every submission costs an attempt.
         * The 10-minute TTL on the attempts counter prevents it from lingering
         * indefinitely if the user never retries.
         */
        redis.opsForValue().increment(attemptsKey);
        redis.expire(attemptsKey, 10, TimeUnit.MINUTES);

        String key = OTP_PREFIX + purpose + ":" + email;
        String stored = redis.opsForValue().get(key);

        if (stored != null && stored.equals(otp)) {
            /* OTP matched — delete it so it cannot be reused */
            redis.delete(key);
            redis.delete(attemptsKey);
            return true;
        }
        return false;
    }

    /**
     * isOnCooldown — returns true if the cooldown key exists in Redis.
     * The presence of the key (regardless of its value) means the user must wait.
     */
    public boolean isOnCooldown(String purpose, String email) {
        email = normalizeIdentifier(email);
        return Boolean.TRUE.equals(redis.hasKey(COOLDOWN_PREFIX + purpose + ":" + email));
    }

    /**
     * setCooldown — stores a cooldown flag in Redis for the given number of seconds.
     * When the TTL expires, Redis automatically removes the key, ending the cooldown.
     */
    public void setCooldown(String purpose, String email, int seconds) {
        email = normalizeIdentifier(email);
        redis.opsForValue().set(COOLDOWN_PREFIX + purpose + ":" + email, "1", seconds, TimeUnit.SECONDS);
    }

    /**
     * getCooldownRemaining — returns the number of seconds left on the cooldown timer.
     * Used by the API to return the exact waiting time to the frontend so it can
     * display a "Resend in Xs" countdown.
     */
    public long getCooldownRemaining(String purpose, String email) {
        email = normalizeIdentifier(email);
        Long ttl = redis.getExpire(COOLDOWN_PREFIX + purpose + ":" + email, TimeUnit.SECONDS);
        return ttl != null && ttl > 0 ? ttl : 0;
    }

    private String normalizeIdentifier(String identifier) {
        if (identifier == null) return null;
        String normalized = identifier.trim();
        return normalized.contains("@") ? normalized.toLowerCase() : normalized;
    }

    /**
     * maskEmail — returns a privacy-safe version of an email for log output.
     * e.g., "john@example.com" → "j***@example.com"
     * Prevents full email addresses from appearing in application logs.
     */
    private String maskEmail(String email) {
        if (email == null || email.isBlank()) return "***";
        int at = email.indexOf('@');
        if (at < 0) {
            String digits = email.replaceAll("\\D", "");
            if (digits.length() >= 4) return "***" + digits.substring(digits.length() - 4);
            return "***";
        }
        if (at <= 2) return "***" + email.substring(at);
        return email.charAt(0) + "***" + email.substring(at);
    }
}
