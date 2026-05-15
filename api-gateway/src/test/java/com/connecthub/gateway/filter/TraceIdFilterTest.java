package com.connecthub.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TraceIdFilterTest {

    private TraceIdFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new TraceIdFilter();
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    @Test
    void filter_generatesTraceIdWhenAbsent() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        verify(chain).filter(argThat(ex -> {
            String traceId = ex.getRequest().getHeaders().getFirst("X-Trace-Id");
            return traceId != null && traceId.length() == 16;
        }));
    }

    @Test
    void filter_reusesExistingTraceId() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/test")
                .header("X-Trace-Id", "existing-trace")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        verify(chain).filter(argThat(ex -> "existing-trace".equals(ex.getRequest().getHeaders().getFirst("X-Trace-Id"))));
    }

    @Test
    void getOrder_returnsMinus2() {
        assertEquals(-2, filter.getOrder());
    }
}
