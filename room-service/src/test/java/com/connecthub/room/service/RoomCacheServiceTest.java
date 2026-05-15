package com.connecthub.room.service;

import com.connecthub.room.entity.RoomMember;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomCacheServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @Mock ObjectMapper objectMapper;

    @InjectMocks RoomCacheService cacheService;

    @Test
    void getCachedMembers_whenCacheMiss_returnsNull() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("room:members:room1")).thenReturn(null);
        assertThat(cacheService.getCachedMembers("room1")).isNull();
    }

    @Test
    void getCachedMembers_whenCacheHit_returnsParsedList() throws Exception {
        when(redis.opsForValue()).thenReturn(valueOps);
        String json = "[{\"memberId\":1,\"roomId\":\"room1\",\"userId\":42}]";
        when(valueOps.get("room:members:room1")).thenReturn(json);
        RoomMember member = RoomMember.builder().memberId(1).roomId("room1").userId(42).build();
        when(objectMapper.readValue(eq(json), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(List.of(member));

        List<RoomMember> result = cacheService.getCachedMembers("room1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserId()).isEqualTo(42);
    }

    @Test
    void cacheMembers_writesJsonWithTtl() throws Exception {
        when(redis.opsForValue()).thenReturn(valueOps);
        RoomMember member = RoomMember.builder().roomId("room1").userId(99).build();
        when(objectMapper.writeValueAsString(anyList())).thenReturn("[{}]");

        cacheService.cacheMembers("room1", List.of(member));

        verify(valueOps).set(eq("room:members:room1"), eq("[{}]"), eq(5L), eq(TimeUnit.MINUTES));
    }

    @Test
    void evict_deletesKey() {
        cacheService.evict("room1");
        verify(redis).delete("room:members:room1");
    }

    @Test
    void getCachedMembers_onDeserializationError_evictsAndReturnsNull() throws Exception {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("room:members:bad")).thenReturn("{bad json}");
        when(objectMapper.readValue(eq("{bad json}"), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenThrow(new com.fasterxml.jackson.core.JsonParseException(null, "parse error"));

        List<RoomMember> result = cacheService.getCachedMembers("bad");

        assertThat(result).isNull();
        verify(redis).delete("room:members:bad"); // eviction happened
    }
}
