package com.connecthub.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * TraceIdFilter — Distributed Request Tracing for the Microservice Architecture
 *
 * PURPOSE:
 *   In a microservice architecture, a single user action (like sending a message)
 *   can trigger HTTP calls across multiple services: gateway → message-service →
 *   websocket-service → notification-service. When something goes wrong, it's very
 *   hard to correlate log entries across those services without a shared identifier.
 *
 *   This filter solves that problem by ensuring every request has a unique X-Trace-Id
 *   header. All downstream services receive this header and should include it in their
 *   own log lines so you can search logs for a single trace ID and see the full
 *   journey of a request across all services.
 *
 * HOW IT WORKS:
 *   1. Check if the incoming request already has an X-Trace-Id header.
 *      External clients (like the React frontend) don't set this, but if an
 *      internal service calls the gateway recursively, the trace ID is preserved.
 *   2. If not present, generate a new 16-character alphanumeric trace ID by taking
 *      a UUID, removing the hyphens, and taking the first 16 characters.
 *      16 characters gives enough uniqueness (2^64 values) without being unwieldy in logs.
 *   3. Add the X-Trace-Id to the forwarded request headers using request.mutate().
 *      Downstream services receive this header and can log it.
 *
 * ORDER:
 *   getOrder() returns -2, which is the lowest order number (highest priority) among
 *   all gateway filters. This ensures the trace ID is injected before any other filter
 *   runs — so even JwtAuthenticationFilter (-1) and RateLimitFilter (0) can include
 *   the trace ID in their error log lines if needed.
 *
 * EXAMPLE TRACE ID: "a3f1b2c4d5e6f7a8"
 */
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        /*
         * Reuse an existing trace ID if the request already has one.
         * This handles the case where an internal service re-enters the gateway
         * with an ongoing trace (rare but possible in complex microservice flows).
         */
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");

        if (traceId == null) {
            /*
             * Generate a compact 16-char trace ID from a UUID.
             * UUID.randomUUID() gives a cryptographically random ID; we strip hyphens
             * and take 16 chars for a balance of uniqueness and log readability.
             */
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        /*
         * Add the trace ID to the forwarded request so all downstream services
         * receive it in their incoming request headers.
         */
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header("X-Trace-Id", traceId)
                .build();

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /**
     * getOrder() = -2 — runs first, before all other gateway filters.
     * This guarantees the trace ID is available for any filter that might
     * want to include it in error messages or logs.
     */
    @Override
    public int getOrder() { return -2; }
}
