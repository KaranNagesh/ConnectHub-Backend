package com.connecthub.gateway.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock ReactiveStringRedisTemplate redisTemplate;
    @Mock ReactiveValueOperations<String, String> valueOps;
    @Mock GatewayFilterChain chain;

    @InjectMocks RateLimitFilter rateLimitFilter;

    @Test
    void whenNoUserId_requestPassesThrough() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/messages").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        when(chain.filter(any())).thenReturn(Mono.empty());

        rateLimitFilter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void whenUnderLimit_requestPassesThrough() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/rooms/list")
                .header("X-User-Id", "42").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(Mono.just(1L)); // count=1, limit=30
        when(redisTemplate.expire(anyString(), any())).thenReturn(Mono.just(Boolean.TRUE));
        when(chain.filter(any())).thenReturn(Mono.empty());

        rateLimitFilter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    @Test
    void whenOverLimit_returns429() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/rooms/list")
                .header("X-User-Id", "42").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(Mono.just(121L)); // count > global limit=120

        rateLimitFilter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(chain, never()).filter(any());
    }

    @Test
    void otpEndpoint_hasLowerLimit() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/auth/send-otp")
                .header("X-User-Id", "10").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(Mono.just(6L)); // count=6 > otp limit=5

        rateLimitFilter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void filterOrder_isZero() {
        assertThat(rateLimitFilter.getOrder()).isEqualTo(0);
    }
}
