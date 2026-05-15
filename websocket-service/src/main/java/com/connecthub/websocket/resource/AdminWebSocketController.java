package com.connecthub.websocket.resource;

import com.connecthub.websocket.config.RedisConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ws/admin")
@RequiredArgsConstructor
public class AdminWebSocketController {

    private static final String WS_USER_SESSIONS_PREFIX = "ws:user:sessions:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messaging;

    @GetMapping("/connections")
    public ResponseEntity<Map<String, Object>> connections(@RequestHeader(value = "X-User-Role", required = false) String role) {
        if (!isAdmin(role)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        Set<String> sessionKeys = redis.keys(WS_USER_SESSIONS_PREFIX + "*");
        long activeUsers = sessionKeys == null ? 0 : sessionKeys.size();
        long activeConnections = 0;
        if (sessionKeys != null) {
            for (String key : sessionKeys) {
                Long sessions = redis.opsForSet().size(key);
                activeConnections += sessions == null ? 0 : sessions;
            }
        }

        return ResponseEntity.ok(Map.of(
                "activeUsers", activeUsers,
                "activeConnections", activeConnections
        ));
    }

    @PostMapping("/broadcast")
    public ResponseEntity<Map<String, Object>> broadcast(
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-User-Id", required = false) String adminId,
            @RequestBody Map<String, String> request) {
        if (!isAdmin(role)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        String title = normalize(request.get("title"), "ConnectHub update");
        String message = normalize(request.get("message"), "");
        if (message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.<String, Object>of("message", "Broadcast message is required"));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "PLATFORM_BROADCAST");
        payload.put("broadcastId", UUID.randomUUID().toString());
        payload.put("title", title);
        payload.put("message", message);
        payload.put("sentBy", adminId);
        payload.put("sentAt", Instant.now().toString());

        try {
            String json = objectMapper.writeValueAsString(payload);
            redis.opsForList().leftPush(RedisConfig.BROADCAST_HISTORY_KEY, json);
            redis.opsForList().trim(RedisConfig.BROADCAST_HISTORY_KEY, 0, 49);
            messaging.convertAndSend("/topic/platform/broadcast", payload);
            redis.convertAndSend(RedisConfig.BROADCAST_CHANNEL, json);
            return ResponseEntity.ok(payload);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.<String, Object>of("message", "Broadcast dispatch failed"));
        }
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.trim().isBlank()) return fallback;
        return value.trim();
    }

    private boolean isAdmin(String role) {
        return "ADMIN".equals(role) || "PLATFORM_ADMIN".equals(role);
    }
}
