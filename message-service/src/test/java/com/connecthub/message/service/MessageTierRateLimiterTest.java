package com.connecthub.message.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class MessageTierRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private MessageTierRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void testTryAcquire_FirstRequest_SetsExpiryAndReturnsTrue() {
        when(valueOperations.increment(anyString())).thenReturn(1L);

        boolean result = rateLimiter.tryAcquire("user123", "FREE");

        assertTrue(result);
        verify(redisTemplate, times(1)).expire(anyString(), any(Duration.class));
        verify(valueOperations, never()).decrement(anyString());
    }

    @Test
    void testTryAcquire_SubsequentRequestWithinLimit_ReturnsTrue() {
        when(valueOperations.increment(anyString())).thenReturn(2L);

        boolean result = rateLimiter.tryAcquire("user456", "PRO");

        assertTrue(result);
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
        verify(valueOperations, never()).decrement(anyString());
    }

    @Test
    void testTryAcquire_RequestExceedsLimit_DecrementsAndReturnsFalse() {
        // FREE tier limit is e.g., 5 or whatever is set in SubscriptionTierLimits.
        // Returning a huge number ensures it fails limit.
        when(valueOperations.increment(anyString())).thenReturn(1000L);

        boolean result = rateLimiter.tryAcquire("user789", "FREE");

        assertFalse(result);
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
        verify(valueOperations, times(1)).decrement(anyString());
    }
}
