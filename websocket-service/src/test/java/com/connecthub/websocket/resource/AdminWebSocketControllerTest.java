package com.connecthub.websocket.resource;

import com.connecthub.websocket.config.RedisConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminWebSocketControllerTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private SimpMessagingTemplate messaging;

    @Mock
    private SetOperations<String, String> setOps;

    @Mock
    private ListOperations<String, String> listOps;

    private AdminWebSocketController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminWebSocketController(redis, new ObjectMapper(), messaging);
    }

    @Test
    void connections_rejectsNonAdmin() {
        ResponseEntity<Map<String, Object>> response = controller.connections("USER");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(redis);
    }

    @Test
    void connections_countsActiveUsersAndSessions() {
        when(redis.keys("ws:user:sessions:*")).thenReturn(Set.of("ws:user:sessions:1", "ws:user:sessions:2"));
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.size("ws:user:sessions:1")).thenReturn(2L);
        when(setOps.size("ws:user:sessions:2")).thenReturn(1L);

        ResponseEntity<Map<String, Object>> response = controller.connections("ADMIN");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("activeUsers", 2L);
        assertThat(response.getBody()).containsEntry("activeConnections", 3L);
    }

    @Test
    void broadcast_rejectsNonAdmin() {
        ResponseEntity<Map<String, Object>> response = controller.broadcast("USER", "1", Map.of("message", "Hello"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(redis, messaging);
    }

    @Test
    void broadcast_rejectsBlankMessage() {
        ResponseEntity<Map<String, Object>> response = controller.broadcast("ADMIN", "1", Map.of("message", " "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Broadcast message is required");
        verifyNoInteractions(messaging);
    }

    @Test
    void broadcast_storesHistoryAndPublishesMessage() {
        when(redis.opsForList()).thenReturn(listOps);

        ResponseEntity<Map<String, Object>> response = controller.broadcast(
                "PLATFORM_ADMIN", "99", Map.of("title", "  Notice  ", "message", "  Hello everyone  "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("title", "Notice");
        assertThat(response.getBody()).containsEntry("message", "Hello everyone");
        assertThat(response.getBody()).containsEntry("sentBy", "99");
        verify(listOps).leftPush(eq(RedisConfig.BROADCAST_HISTORY_KEY), any(String.class));
        verify(listOps).trim(RedisConfig.BROADCAST_HISTORY_KEY, 0, 49);
        verify(messaging).convertAndSend(eq("/topic/platform/broadcast"), any(Map.class));
        verify(redis).convertAndSend(eq(RedisConfig.BROADCAST_CHANNEL), any(String.class));
    }

    @Test
    void broadcast_returnsServerErrorWhenRedisFails() {
        when(redis.opsForList()).thenReturn(listOps);
        when(listOps.leftPush(eq(RedisConfig.BROADCAST_HISTORY_KEY), any(String.class)))
                .thenThrow(new IllegalStateException("redis down"));

        ResponseEntity<Map<String, Object>> response = controller.broadcast("ADMIN", "1", Map.of("message", "Hello"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(messaging, never()).convertAndSend(eq("/topic/platform/broadcast"), any(Map.class));
    }
}
