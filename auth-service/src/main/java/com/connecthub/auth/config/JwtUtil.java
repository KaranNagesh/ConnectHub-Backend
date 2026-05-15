package com.connecthub.auth.config;

import com.connecthub.auth.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JwtUtil — JSON Web Token Generation and Validation Utility
 *
 * PURPOSE:
 *   Centralizes all JWT operations for the auth-service. It is the single place
 *   where tokens are created, parsed, and validated. All other services in the
 *   architecture receive tokens from the frontend and validate them at the gateway,
 *   but only the auth-service issues tokens — using this class.
 *
 * TOKEN TYPES ISSUED:
 *
 *   1. Access Token (generateAccessToken):
 *      - Short-lived (default 24 hours, configurable via jwt.access-token-expiry in ms)
 *      - Contains user identity claims: email, username, role, subscriptionTier
 *      - Subject ("sub") is the numeric userId as a string
 *      - The API gateway reads these claims and forwards them as X-User-* headers
 *
 *   2. Refresh Token (generateRefreshToken):
 *      - Long-lived (default 7 days, configurable via jwt.refresh-token-expiry in ms)
 *      - Contains only the user ID and a "type": "refresh" claim (no profile data)
 *      - Used by the frontend to request a new access token when it expires
 *      - The auth-service refreshToken endpoint issues a fresh access token in exchange
 *
 *   3. Reset Token (generateResetToken):
 *      - Very short-lived (hardcoded 15 minutes — 900000ms)
 *      - Contains a "purpose": "PASSWORD_RESET" claim for additional validation
 *      - Issued after a user successfully verifies their password-reset OTP
 *      - Passed back to the resetPassword endpoint to authorize the password change
 *      - Using a JWT here means no server-side state is needed for reset sessions
 *
 * KEY MANAGEMENT:
 *   The HMAC-SHA256 signing key is derived from a Base64-encoded secret stored in
 *   application.yml (jwt.secret). The same secret is configured in the API Gateway
 *   so both can verify tokens independently without contacting each other.
 *   The key() method decodes the Base64 string and wraps it in an HMAC key object.
 *
 * CLAIMS IN THE ACCESS TOKEN:
 *   - sub (subject)       — userId as a string (e.g., "42")
 *   - email               — user's email address
 *   - username            — user's username
 *   - role                — USER, ADMIN, GUEST, etc.
 *   - subscriptionTier    — FREE or PRO (used by gateway to set X-Subscription-Tier)
 *   - iat (issued at)     — Unix timestamp of token issuance
 *   - exp (expiry)        — Unix timestamp of token expiry
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String jwtSecret;

    /** Access token lifetime in milliseconds. Default 86400000 = 24 hours. */
    @Value("${jwt.access-token-expiry:86400000}")
    private long accessExpiry;

    /** Refresh token lifetime in milliseconds. Default 604800000 = 7 days. */
    @Value("${jwt.refresh-token-expiry:604800000}")
    private long refreshExpiry;

    /**
     * key() — builds an HMAC-SHA signing key from the Base64-encoded secret.
     * Called on every token operation; the key object is lightweight to create.
     */
    private SecretKey key() {
        return Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtSecret));
    }

    /**
     * generateAccessToken — creates a signed JWT containing the user's full identity.
     * The subscriptionTier defaults to "FREE" if the user entity doesn't have one set
     * (e.g., for guest accounts or users created before the tier field was added).
     */
    public String generateAccessToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("email", user.getEmail());
        claims.put("username", user.getUsername());
        claims.put("role", user.getRole());
        claims.put("subscriptionTier",
                user.getSubscriptionTier() != null ? user.getSubscriptionTier() : "FREE");
        return Jwts.builder()
                .subject(String.valueOf(user.getUserId()))
                .claims(claims)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpiry))
                .signWith(key())
                .compact();
    }

    /**
     * generateRefreshToken — creates a minimal long-lived token for session renewal.
     * Intentionally contains only the user ID and type — no profile data.
     * If the user's profile changes (e.g., role upgrade), the next access token
     * generated from this refresh token will include the updated claims.
     */
    public String generateRefreshToken(User user) {
        return Jwts.builder()
                .subject(String.valueOf(user.getUserId()))
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpiry))
                .signWith(key())
                .compact();
    }

    /**
     * generateResetToken — creates a 15-minute token that authorizes a password reset.
     * The "purpose" claim prevents this token from being used as a regular access token.
     * The resetPassword endpoint checks this claim before allowing the password change.
     */
    public String generateResetToken(int userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("purpose", "PASSWORD_RESET")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 900000))
                .signWith(key())
                .compact();
    }

    /**
     * parseToken — parses and verifies a JWT string.
     * Throws a JwtException subclass if the token is expired, malformed, or
     * signed with a different key. Callers should catch JwtException.
     */
    public Claims parseToken(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }

    /**
     * isValid — returns true if the token can be parsed successfully.
     * Used to check tokens without needing to handle exceptions at the call site.
     */
    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    /** getUserId — extracts the numeric user ID from the token's "sub" claim. */
    public int getUserId(String token) {
        return Integer.parseInt(parseToken(token).getSubject());
    }

    /** getAccessExpiry — exposes the configured access token lifetime (in ms). */
    public long getAccessExpiry() {
        return accessExpiry;
    }
}
