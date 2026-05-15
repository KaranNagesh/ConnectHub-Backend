package com.connecthub.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * RateLimitFilter — Redis-Backed Per-User, Per-Action Rate Limiter at the Gateway
 *
 * PURPOSE:
 *   Protects the backend from being overwhelmed by too many requests from a single
 *   user. It uses Redis as a fast, shared counter so limits are enforced consistently
 *   even if multiple gateway instances are running.
 *
 *   The filter runs AFTER JwtAuthenticationFilter (order 0 vs -1), so by the time
 *   this filter executes, the X-User-Id header has already been set on the request.
 *
 * HOW THE RATE LIMITING WORKS (sliding-window counter per minute):
 *   1. A Redis key is constructed as: ratelimit:{userId}:{action}:{epochMinute}
 *      The epochMinute is the current Unix time divided by 60 (whole minutes since epoch).
 *      This effectively creates a 1-minute fixed window per action per user.
 *   2. Redis INCR atomically increments the counter for that key.
 *      If this is the first request in the window (count == 1), a 2-minute TTL is set
 *      so the key expires on its own and Redis never accumulates stale data.
 *   3. If the count exceeds the limit for that action, the filter returns HTTP 429
 *      Too Many Requests and adds three response headers to help the client understand
 *      what happened:
 *        X-RateLimit-Action      — which action bucket was exceeded (e.g., "otp")
 *        X-RateLimit-Limit       — what the limit is for that action
 *        X-RateLimit-Retry-After — how many seconds to wait (always 60 for 1-min windows)
 *
 * ACTION BUCKETS:
 *   "otp"    — OTP request endpoints (/api/v1/auth/**otp**) — limit: 5/min
 *              Strict limit prevents SMS/email OTP abuse.
 *   "global" — All other authenticated endpoints — limit: 120/min
 *              General protection against automation or burst traffic.
 *
 * WHY MESSAGE AND MEDIA LIMITS ARE NOT HERE:
 *   Message-service and media-service have their own per-tier rate limiters
 *   (MessageTierRateLimiter) because their limits differ between FREE and PRO users
 *   and because WebSocket messages bypass the gateway HTTP layer entirely.
 *   We skip /api/v1/messages and /api/v1/media in this filter to avoid double-counting.
 *
 * WHY REDIS AND NOT AN IN-MEMORY MAP:
 *   If multiple API gateway replicas run (horizontal scaling), an in-memory map
 *   would give each replica its own counter — user could hit 5× the limit.
 *   Redis is a single shared data store, so all replicas see the same counter.
 *   ReactiveStringRedisTemplate is the non-blocking (reactive/WebFlux) variant
 *   so it works with Spring Cloud Gateway's reactive pipeline without blocking.
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final ReactiveStringRedisTemplate redisTemplate;

    private static final String ACTION_OTP    = "otp";
    private static final String ACTION_GLOBAL = "global";

    private static final int LIMIT_OTP    = 5;
    private static final int LIMIT_GLOBAL = 120;

    public RateLimitFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        /*
         * X-User-Id is added by JwtAuthenticationFilter for authenticated requests.
         * If it's missing, the request is unauthenticated — JwtAuthenticationFilter
         * would have already rejected it for protected routes, and open routes don't
         * need rate limiting here (they have their own limits in auth-service).
         */
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        if (userId == null) {
            return chain.filter(exchange);
        }

        /*
         * Skip message and media endpoints — those services have their own per-tier
         * rate limiters that also apply to WebSocket traffic which bypasses this filter.
         */
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/api/v1/messages") || path.startsWith("/api/v1/media")) {
            return chain.filter(exchange);
        }

        String action = resolveAction(path);
        int    limit  = resolveLimit(action);

        /*
         * epochMinute — current Unix timestamp divided by 60, giving a whole-minute bucket.
         * Every request in the same calendar minute increments the same Redis key.
         * At the top of each new minute, a new key is created automatically.
         */
        long   minute = Instant.now().getEpochSecond() / 60;
        String key    = "ratelimit:" + userId + ":" + action + ":" + minute;

        /*
         * Redis INCR is atomic — safe for concurrent requests from the same user.
         * flatMap processes the result of the increment reactively (non-blocking).
         */
        return redisTemplate.opsForValue().increment(key)
                .flatMap(count -> {
                    if (count == 1) {
                        /*
                         * First request this minute: set a 2-minute TTL so the key
                         * automatically expires after the window closes. The extra
                         * minute of grace prevents timing edge cases where a request
                         * arrives exactly as the clock ticks over.
                         */
                        redisTemplate.expire(key, Duration.ofMinutes(2)).subscribe();
                    }
                    if (count > limit) {
                        /*
                         * Limit exceeded — return 429 with informational headers so
                         * the frontend knows what happened and when to retry.
                         */
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        exchange.getResponse().getHeaders().add("X-RateLimit-Action", action);
                        exchange.getResponse().getHeaders().add("X-RateLimit-Limit", String.valueOf(limit));
                        exchange.getResponse().getHeaders().add("X-RateLimit-Retry-After", "60");
                        return exchange.getResponse().setComplete();
                    }
                    return chain.filter(exchange);
                });
    }

    /**
     * resolveAction — maps a request path to a rate-limit action bucket.
     * OTP endpoints contain "otp" in the path (e.g., /api/v1/auth/send-otp,
     * /api/v1/auth/login-otp). Everything else falls into the "global" bucket.
     */
    private String resolveAction(String path) {
        if (path.startsWith("/api/v1/auth") && path.contains("otp")) {
            return ACTION_OTP;
        }
        return ACTION_GLOBAL;
    }

    /**
     * resolveLimit — returns the request-per-minute limit for the given action.
     */
    private int resolveLimit(String action) {
        return switch (action) {
            case ACTION_OTP -> LIMIT_OTP;
            default         -> LIMIT_GLOBAL;
        };
    }

    /**
     * getOrder() = 0, running after JwtAuthenticationFilter (-1) so that
     * X-User-Id is already available in the request headers when we arrive here.
     */
    @Override
    public int getOrder() { return 0; }
}
