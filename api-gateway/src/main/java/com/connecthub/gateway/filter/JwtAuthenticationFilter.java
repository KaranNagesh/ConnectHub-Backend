package com.connecthub.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
//import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

/**
 * JwtAuthenticationFilter — Global JWT Verification Filter for the API Gateway
 *
 * PURPOSE:
 *   This is the central security gate for the entire ConnectHub backend. Every
 *   HTTP request that comes into the API Gateway passes through this filter before
 *   being forwarded to any downstream microservice.
 *
 *   The filter has two responsibilities:
 *     1. Verify that the incoming request carries a valid JWT access token (for
 *        protected endpoints). If the token is missing or invalid, return 401 Unauthorized.
 *     2. Extract the user's identity claims from the JWT and forward them as internal
 *        HTTP headers (X-User-Id, X-User-Email, etc.) to downstream services, so
 *        those services don't need to decode JWTs themselves — they just read the headers.
 *
 * HOW IT WORKS:
 *   1. The filter checks if the request path starts with any of the OPEN_ENDPOINTS
 *      prefixes. Open endpoints are public routes that don't require a JWT (e.g.,
 *      /api/v1/auth/ for login/register, /ws/ for WebSocket handshakes).
 *   2. Admin routes (/api/v1/auth/admin/**) are special — even though they start
 *      with /api/v1/auth/, they are NOT open. The isAdminRoute flag ensures they
 *      always go through full JWT validation + ADMIN role check.
 *   3. For protected routes: the Authorization header is read, the "Bearer " prefix
 *      is stripped, and the JWT is parsed and verified using the shared secret key
 *      (loaded from application.yml via @Value).
 *   4. The JWT claims are extracted and added as trusted internal headers on the
 *      mutated request. Downstream services trust these headers because they can
 *      only be set by the gateway (external clients cannot inject them past the filter).
 *   5. X-Subscription-Tier is added so downstream services (like message-service)
 *      can apply per-tier rate limits without needing to look up the subscription.
 *
 * ORDER:
 *   getOrder() returns -1, meaning this filter runs before most other filters.
 *   TraceIdFilter runs at -2 (first) so the trace ID is available in logs for all
 *   subsequent filters including this one.
 *
 * HEADERS FORWARDED TO DOWNSTREAM SERVICES:
 *   X-User-Id          — the user's numeric ID (from JWT "sub" claim)
 *   X-User-Email       — the user's email address
 *   X-User-Username    — the user's username
 *   X-User-Role        — the user's role (USER, ADMIN, PLATFORM_ADMIN)
 *   X-Subscription-Tier — FREE or PRO (used for per-tier rate limits)
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    @Value("${jwt.secret}")
    private String jwtSecret;

    /**
     * OPEN_ENDPOINTS — paths that do NOT require JWT authentication.
     * Any request whose path starts with one of these prefixes bypasses
     * JWT validation and is forwarded directly to the downstream service.
     *
     * Why /ws/ is open: WebSocket connections are upgraded from HTTP, and the
     * STOMP client sends the JWT in the CONNECT frame header, not in the HTTP
     * Authorization header. The WebSocket service validates the JWT separately.
     *
     * Why /api/v1/payments/webhook is open: Razorpay calls this endpoint with
     * its own signature-based authentication, not a user JWT.
     */
    private static final List<String> OPEN_ENDPOINTS = List.of(
            "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/guest",
            "/api/v1/auth/verify-registration-otp", "/api/v1/auth/resend-registration-otp",
            "/api/v1/auth/forgot-password", "/api/v1/auth/verify-reset-otp",
            "/api/v1/auth/reset-password", "/api/v1/auth/refresh", "/api/v1/auth/validate",
            "/api/v1/auth/phone/request-otp", "/api/v1/auth/phone/verify-otp",
            "/api/v1/auth/login/email/request-otp", "/api/v1/auth/login/email/verify-otp",
            "/api/v1/auth/login/phone/request-otp", "/api/v1/auth/login/phone/verify-otp",
            "/api/v1/auth/avatars/",
            "/api/v1/rooms/avatars/",
            "/oauth2/", "/login/oauth2/",
            "/ws", "/ws/",
            "/api/v1/payments/webhook",
            "/actuator/", "/swagger-ui/", "/v3/api-docs/", "/eureka",
            "/auth-docs/", "/room-docs/", "/message-docs/", "/media-docs/",
            "/presence-docs/", "/notification-docs/", "/websocket-docs/", "/payment-docs/"
    );

    /**
     * Admin routes start with /api/v1/auth/admin but must NOT be treated as open
     * even though they match the /api/v1/auth/ prefix. This constant lets us detect
     * and override the open-endpoint shortcut for admin paths.
     */
    private static final String ADMIN_PATH_PREFIX = "/api/v1/auth/admin";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        /*
         * Admin routes must always be authenticated even though their path starts
         * with /api/v1/auth/ (which is normally open for login/register endpoints).
         * We detect admin paths first and skip the open-endpoint shortcut.
         */
        boolean isAdminRoute = path.startsWith(ADMIN_PATH_PREFIX);

        if (!isAdminRoute && OPEN_ENDPOINTS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        /*
         * Extract the Bearer token from the Authorization header.
         * If missing or malformed, return 401 immediately without touching the downstream.
         */
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        try {
            String token = authHeader.substring(7);

            /*
             * Build the same HMAC-SHA key used by auth-service. The secret can be
             * Base64 or plain text; short plain-text secrets are stretched to 256 bits.
             */
            SecretKey key = key();
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();

            /*
             * Admin role guard — only users with role=ADMIN or PLATFORM_ADMIN can access admin routes.
             * A valid JWT with role=USER would still get 403 Forbidden here.
             */
            if (isAdminRoute) {
                String role = claims.get("role", String.class);
                if (!"ADMIN".equals(role) && !"PLATFORM_ADMIN".equals(role)) {
                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                    return exchange.getResponse().setComplete();
                }
            }

            /*
             * Default the subscription tier to FREE if not present in the JWT.
             * This handles tokens issued before the tier claim was added, or
             * guest accounts that have no subscription record.
             */

            /*
             * Mutate the request to add trusted internal headers.
             * Downstream services use these headers to identify the caller without
             * needing to decode or re-verify the JWT themselves.
             *
             *
             */

            String rawTier = claims.get("subscriptionTier", String.class);
            String tier = rawTier == null || rawTier.isBlank() ? "FREE" : rawTier;

            String userId = claims.getSubject();
            String email = claims.get("email", String.class);
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);

            ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate();

            requestBuilder.headers(headers -> {
                headers.remove("X-User-Id");
                headers.remove("X-User-Email");
                headers.remove("X-User-Username");
                headers.remove("X-User-Role");
                headers.remove("X-Subscription-Tier");
            });

            if (userId != null && !userId.isBlank()) {
                requestBuilder.header("X-User-Id", userId);
            }
            if (email != null && !email.isBlank()) {
                requestBuilder.header("X-User-Email", email);
            }
            if (username != null && !username.isBlank()) {
                requestBuilder.header("X-User-Username", username);
            }
            if (role != null && !role.isBlank()) {
                requestBuilder.header("X-User-Role", role);
            }
            requestBuilder.header("X-Subscription-Tier", tier);

            ServerHttpRequest mutated = requestBuilder.build();
            return chain.filter(exchange.mutate().request(mutated).build());



        } catch (Exception e) {
            /*
             * Any JWT validation failure (expired, invalid signature, malformed)
             * results in a 401 Unauthorized. We don't expose the specific error
             * to the client for security reasons.
             */
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    /**
     * getOrder() = -1 so this filter runs after TraceIdFilter (-2) but before
     * RateLimitFilter (0). This ordering ensures:
     *   1. Every request gets a trace ID (TraceIdFilter).
     *   2. JWT is validated and user identity headers are added.
     *   3. Rate limiting can then use the X-User-Id header set by this filter.
     */
    @Override
    public int getOrder() { return -1; }

    private SecretKey key() {
        byte[] secretBytes = decodeSecret();
        if (secretBytes.length < 32) {
            secretBytes = sha256(secretBytes);
        }
        return Keys.hmacShaKeyFor(secretBytes);
    }

    private byte[] decodeSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("JWT secret must not be blank");
        }

        String secret = jwtSecret.trim();
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException ex) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
