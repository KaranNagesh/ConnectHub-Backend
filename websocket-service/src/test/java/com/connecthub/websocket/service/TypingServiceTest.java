package com.connecthub.websocket.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TypingServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    @InjectMocks TypingService typingService;

    @Test
    void setTyping_storesKeyWithFourSecondTtl() {
        when(redis.opsForValue()).thenReturn(valueOps);
        typingService.setTyping("room1", 42);
        verify(valueOps).set(eq("typing:room1:42"), eq("1"), eq(4L), eq(TimeUnit.SECONDS));
    }

    @Test
    void clearTyping_deletesKey() {
        typingService.clearTyping("room1", 42);
        verify(redis).delete("typing:room1:42");
    }

    @Test
    void isTyping_whenKeyExists_returnsTrue() {
        when(redis.hasKey("typing:room1:42")).thenReturn(Boolean.TRUE);
        assertThat(typingService.isTyping("room1", 42)).isTrue();
    }

    @Test
    void isTyping_whenKeyAbsent_returnsFalse() {
        when(redis.hasKey("typing:room1:42")).thenReturn(null);
        assertThat(typingService.isTyping("room1", 42)).isFalse();
    }
}
