package com.connecthub.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Request/response logging for the API gateway.
 *
 * The filter logs at DEBUG level so request tracing can be enabled when needed
 * without blocking the reactive event loop on synchronous stdout writes.
 */
@Component
public class LoggingFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!log.isDebugEnabled()) {
            return chain.filter(exchange);
        }

        log.debug("GATEWAY REQ: {} {}", exchange.getRequest().getMethod(), exchange.getRequest().getURI());

        return chain.filter(exchange)
                .doOnSuccess(v -> log.debug("GATEWAY RES: {}", exchange.getResponse().getStatusCode()))
                .doOnError(err -> log.debug("GATEWAY ERR: {}", err.getMessage()));
    }
}
