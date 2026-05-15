package com.connecthub.media.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaUploadRateLimiterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @InjectMocks private MediaUploadRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void tryAcquire_firstRequest_setsExpiryAndReturnsTrue() {
        when(valueOps.increment(anyString())).thenReturn(1L);

        boolean result = rateLimiter.tryAcquire("42", "FREE");

        assertThat(result).isTrue();
        verify(redisTemplate).expire(anyString(), any());
    }

    @Test
    void tryAcquire_withinLimit_returnsTrue() {
        when(valueOps.increment(anyString())).thenReturn(3L);

        boolean result = rateLimiter.tryAcquire("42", "FREE");

        assertThat(result).isTrue();
        verify(redisTemplate, never()).expire(any(), any());
    }

    @Test
    void tryAcquire_exceedsLimit_decrementsAndReturnsFalse() {
        // FREE limit is 5; count 6 exceeds it
        when(valueOps.increment(anyString())).thenReturn(6L);

        boolean result = rateLimiter.tryAcquire("42", "FREE");

        assertThat(result).isFalse();
        verify(valueOps).decrement(anyString());
    }

    @Test
    void tryAcquire_proTierWithinLimit_returnsTrue() {
        // PRO limit is 30
        when(valueOps.increment(anyString())).thenReturn(20L);

        boolean result = rateLimiter.tryAcquire("7", "PRO");

        assertThat(result).isTrue();
    }

    @Test
    void tryAcquire_proTierExceedsLimit_returnsFalse() {
        when(valueOps.increment(anyString())).thenReturn(31L);

        boolean result = rateLimiter.tryAcquire("7", "PRO");

        assertThat(result).isFalse();
        verify(valueOps).decrement(anyString());
    }

    @Test
    void tryAcquire_nullCountFromRedis_returnsTrue() {
        when(valueOps.increment(anyString())).thenReturn(null);

        boolean result = rateLimiter.tryAcquire("1", "FREE");

        assertThat(result).isTrue();
    }
}
