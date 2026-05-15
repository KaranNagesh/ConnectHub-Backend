package com.connecthub.websocket.resource;

import com.connecthub.websocket.config.RedisConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformBroadcastControllerTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ListOperations<String, String> listOps;

    private PlatformBroadcastController controller;

    @BeforeEach
    void setUp() {
        controller = new PlatformBroadcastController(redis, new ObjectMapper());
    }

    @Test
    void recent_requiresAuthenticatedUser() {
        ResponseEntity<List<Map<String, Object>>> response = controller.recent(" ", 20, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(redis);
    }

    @Test
    void recent_returnsEmptyListWhenNoHistoryExists() {
        when(redis.opsForList()).thenReturn(listOps);
        when(listOps.range(RedisConfig.BROADCAST_HISTORY_KEY, 0, 19)).thenReturn(List.of());

        ResponseEntity<List<Map<String, Object>>> response = controller.recent("5", 20, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void recent_capsLimitSkipsInvalidJsonAndFiltersBySince() {
        Instant since = Instant.parse("2026-05-08T10:00:00Z");
        String oldEvent = "{\"broadcastId\":\"old\",\"sentAt\":\"2026-05-08T09:00:00Z\"}";
        String newEvent = "{\"broadcastId\":\"new\",\"sentAt\":\"2026-05-08T11:00:00Z\"}";
        String noDateEvent = "{\"broadcastId\":\"nodate\"}";
        when(redis.opsForList()).thenReturn(listOps);
        when(listOps.range(RedisConfig.BROADCAST_HISTORY_KEY, 0, 49))
                .thenReturn(List.of("not-json", oldEvent, newEvent, noDateEvent));

        ResponseEntity<List<Map<String, Object>>> response = controller.recent("5", 99, since.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .extracting(event -> event.get("broadcastId"))
                .containsExactly("new", "nodate");
    }

    @Test
    void recent_invalidSinceReturnsAllReadableEvents() {
        String event = "{\"broadcastId\":\"one\",\"sentAt\":\"2026-05-08T09:00:00Z\"}";
        when(redis.opsForList()).thenReturn(listOps);
        when(listOps.range(RedisConfig.BROADCAST_HISTORY_KEY, 0, 0)).thenReturn(List.of(event));

        ResponseEntity<List<Map<String, Object>>> response = controller.recent("5", -1, "bad-date");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0)).containsEntry("broadcastId", "one");
    }
}
