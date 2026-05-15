package com.connecthub.websocket.resource;

import com.connecthub.websocket.config.RedisConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/ws/broadcasts")
@RequiredArgsConstructor
public class PlatformBroadcastController {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @GetMapping("/recent")
    public ResponseEntity<List<Map<String, Object>>> recent(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "since", required = false) String since) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        int cappedLimit = Math.max(1, Math.min(limit, 50));
        List<String> raw = redis.opsForList().range(RedisConfig.BROADCAST_HISTORY_KEY, 0L, cappedLimit - 1L);
        if (raw == null || raw.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        Instant sinceInstant = parseInstant(since);
        List<Map<String, Object>> broadcasts = raw.stream()
                .map(this::readBroadcast)
                .filter(Objects::nonNull)
                .filter(event -> isAfter(event, sinceInstant))
                .toList();
        return ResponseEntity.ok(broadcasts);
    }

    private Map<String, Object> readBroadcast(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ignored) {
            return null;
        }
    }

    private Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isAfter(Map<String, Object> event, Instant since) {
        if (since == null) return true;
        Instant sentAt = parseInstant(String.valueOf(event.getOrDefault("sentAt", "")));
        return sentAt == null || sentAt.isAfter(since);
    }
}
