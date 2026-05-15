package com.connecthub.websocket.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * TypingService — Redis TTL-Based Typing Indicator State
 *
 * PURPOSE:
 *   Tracks which users are currently typing in which rooms by storing ephemeral
 *   keys in Redis. The frontend sends a /app/chat.typing STOMP frame periodically
 *   while the user is typing, and this service records that state.
 *
 * HOW IT WORKS (sliding window TTL):
 *   When a user types, setTyping() is called which stores a Redis key:
 *     Key: "typing:{roomId}:{userId}"    Value: "1"    TTL: 4 seconds
 *
 *   If the user keeps typing and sends another typing frame before the 4 seconds
 *   expire, the key's TTL is RESET to 4 seconds (sliding window debounce).
 *   If the user stops typing and sends no more frames, the key expires automatically
 *   and the typing indicator disappears — no explicit "stopped typing" event needed.
 *
 *   This TTL-based approach is crash-safe: if the client's WebSocket connection drops
 *   mid-session, the typing indicator automatically clears within 4 seconds without
 *   requiring any cleanup logic from the disconnect handler.
 *
 * WHY REDIS AND NOT IN-MEMORY:
 *   If multiple websocket-service pods are running, User A (on pod 1) typing should
 *   be visible to User B (on pod 2). Redis is shared across all pods, so the typing
 *   state is globally consistent. An in-memory map would only work for a single pod.
 *
 * HOW THE FRONTEND USES THIS:
 *   The frontend sends /app/chat.typing every ~2 seconds while the user is typing.
 *   ChatWebSocketHandler calls setTyping() and then broadcasts the typing payload
 *   directly to /topic/room/{roomId}/typing via STOMP. The TypingIndicator component
 *   renders the "Alice is typing..." banner based on these WebSocket events.
 *   The Redis state is used by DeliveryService to avoid redundant pushes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TypingService {

    private final StringRedisTemplate redis;

    private static final String TYPING_PREFIX      = "typing:";
    private static final long   TYPING_TTL_SECONDS = 4;

    /**
     * setTyping — marks a user as currently typing in a room.
     * Called on every /app/chat.typing frame. Resets the TTL each time so the
     * indicator stays alive as long as the user keeps typing.
     */
    public void setTyping(String roomId, int userId) {
        String key = buildKey(roomId, userId);
        redis.opsForValue().set(key, "1", TYPING_TTL_SECONDS, TimeUnit.SECONDS);
        log.trace("Typing set  room={} user={}", roomId, userId);
    }

    /**
     * clearTyping — explicitly removes the typing indicator.
     * Called when the user sends a message (they stopped typing) or when their
     * WebSocket session disconnects cleanly via the disconnect event handler.
     */
    public void clearTyping(String roomId, int userId) {
        redis.delete(buildKey(roomId, userId));
        log.trace("Typing cleared room={} user={}", roomId, userId);
    }

    /**
     * isTyping — checks whether a user is currently marked as typing.
     * Returns true if the Redis key exists (not expired).
     */
    public boolean isTyping(String roomId, int userId) {
        return Boolean.TRUE.equals(redis.hasKey(buildKey(roomId, userId)));
    }

    private String buildKey(String roomId, int userId) {
        return TYPING_PREFIX + roomId + ":" + userId;
    }
}
