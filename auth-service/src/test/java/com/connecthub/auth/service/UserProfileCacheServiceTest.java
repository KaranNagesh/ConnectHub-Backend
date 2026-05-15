package com.connecthub.auth.service;

import com.connecthub.auth.dto.UserProfileDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserProfileCacheServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @Mock ObjectMapper objectMapper;

    @InjectMocks UserProfileCacheService cacheService;


    @Test
    void getCachedProfile_whenMiss_returnsNull() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("user:profile:1")).thenReturn(null);
        assertThat(cacheService.getCachedProfile(1)).isNull();
    }

    @Test
    void getCachedProfile_whenRedisUnavailable_returnsNull() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("user:profile:1"))
                .thenThrow(new DataAccessResourceFailureException("Redis down"));

        assertThat(cacheService.getCachedProfile(1)).isNull();
    }

    @Test
    void getCachedProfile_whenHit_returnsProfile() throws Exception {
        when(redis.opsForValue()).thenReturn(valueOps);
        String json = "{\"userId\":1,\"username\":\"bob\"}";
        when(valueOps.get("user:profile:1")).thenReturn(json);
        UserProfileDto dto = UserProfileDto.builder().userId(1).username("bob").build();
        when(objectMapper.readValue(eq(json), eq(UserProfileDto.class))).thenReturn(dto);

        UserProfileDto result = cacheService.getCachedProfile(1);

        assertThat(result.getUsername()).isEqualTo("bob");
    }

    @Test
    void cacheProfile_writesWithTtl() throws Exception {
        when(redis.opsForValue()).thenReturn(valueOps);
        UserProfileDto dto = UserProfileDto.builder().userId(5).username("alice").build();
        when(objectMapper.writeValueAsString(dto)).thenReturn("{\"userId\":5}");

        cacheService.cacheProfile(5, dto);

        verify(valueOps).set(eq("user:profile:5"), eq("{\"userId\":5}"), eq(30L), eq(TimeUnit.MINUTES));
    }

    @Test
    void cacheProfile_whenRedisUnavailable_doesNotThrow() throws Exception {
        when(redis.opsForValue()).thenReturn(valueOps);
        UserProfileDto dto = UserProfileDto.builder().userId(5).username("alice").build();
        when(objectMapper.writeValueAsString(dto)).thenReturn("{\"userId\":5}");
        doThrow(new DataAccessResourceFailureException("Redis down"))
                .when(valueOps).set(eq("user:profile:5"), eq("{\"userId\":5}"), eq(30L), eq(TimeUnit.MINUTES));

        assertDoesNotThrow(() -> cacheService.cacheProfile(5, dto));
    }

    @Test
    void evict_deletesKey() {
        cacheService.evict(7);
        verify(redis).delete("user:profile:7");
    }

    @Test
    void evict_whenRedisUnavailable_doesNotThrow() {
        doThrow(new DataAccessResourceFailureException("Redis down"))
                .when(redis).delete("user:profile:7");

        assertDoesNotThrow(() -> cacheService.evict(7));
    }

    @Test
    void getCachedProfile_onDeserializationError_evictsAndReturnsNull() throws Exception {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("user:profile:99")).thenReturn("{bad}");
        when(objectMapper.readValue(eq("{bad}"), eq(UserProfileDto.class)))
                .thenThrow(new com.fasterxml.jackson.core.JsonParseException(null, "err"));

        UserProfileDto result = cacheService.getCachedProfile(99);

        assertThat(result).isNull();
        verify(redis).delete("user:profile:99");
    }
}
