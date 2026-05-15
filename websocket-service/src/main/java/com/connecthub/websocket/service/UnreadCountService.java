package com.connecthub.websocket.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * UnreadCountService — Per-User Per-Room Unread Message Counter in Redis
 *
 * PURPOSE:
 *   Maintains a count of unread messages for each user in each room. These counts
 *   power the unread badge numbers shown on room entries in the sidebar.
 *
 *   When a new message arrives in a room and a member is not actively viewing it,
 *   their counter is incremented. When they open the room and send a read receipt,
 *   the counter is reset to zero.
 *
 * REDIS KEY STRUCTURE:
 *   Key:  "unread:{userId}:{roomId}"   (e.g., "unread:42:room_abc123")
 *   Type: Integer string (INCR/DEL operations)
 *   TTL:  None — counters persist until explicitly reset by a read receipt.
 *
 * WHY REDIS INCR IS SAFE FOR CONCURRENT WRITES:
 *   Redis INCR is an atomic operation. If multiple messages arrive simultaneously
 *   (e.g., someone sends a burst of messages), each INCR call is processed one at
 *   a time by Redis's single-threaded command processor. There are no race conditions
 *   or lost increments, unlike an in-memory counter with concurrent writes.
 *
 * LIFECYCLE:
 *   - increment(): called by DeliveryService when a message is delivered to an offline
 *     or non-viewing member. Even online users get incremented if they're in a different
 *     room — the frontend resets the badge when they switch to that room.
 *   - reset(): called when the user sends a /chat.read STOMP frame (they opened the room).
 *     Deletes the Redis key entirely (which reads as 0).
 *   - getCount(): called when a user reconnects to sync their current badge counts.
 *   - getAllForUser(): called to load all room badge counts at once on sidebar render.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnreadCountService {

    private final StringRedisTemplate redis;

    private static final String UNREAD_PREFIX = "unread:";

    /**
     * increment — atomically adds 1 to the unread count for a user in a room.
     * Uses Redis INCR which is atomic and cannot race with other increments.
     */
    public void increment(int userId, String roomId) {
        String key = buildKey(userId, roomId);
        Long count = redis.opsForValue().increment(key);
        log.debug("Unread++ user={} room={} count={}", userId, roomId, count);
    }

    /**
     * reset — clears the unread count for a user in a room (sets to 0).
     * Called when the user sends a read receipt for this room.
     * Deletes the key entirely rather than setting to 0 so that absence of a key
     * and a zero count are treated identically by getCount().
     */
    public void reset(int userId, String roomId) {
        redis.delete(buildKey(userId, roomId));
        log.debug("Unread reset user={} room={}", userId, roomId);
    }

    /**
     * getCount — returns the current unread count for a user in a specific room.
     * Returns 0 if the key doesn't exist (no unread messages) or if the value
     * cannot be parsed as a number (defensive handling of data corruption).
     */
    public long getCount(int userId, String roomId) {
        String val = redis.opsForValue().get(buildKey(userId, roomId));
        if (val == null) return 0L;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * getAllForUser — returns a map of roomId → unreadCount for all rooms where
     * the user has unread messages. Used when the user connects or refreshes the
     * sidebar to show all current badge counts in one call.
     *
     * Uses Redis KEYS pattern matching (unread:{userId}:*) which is O(N) over the
     * keyspace. Acceptable here because the number of unread room keys per user
     * is small (bounded by the number of rooms the user is in).
     */
    public java.util.Map<String, Long> getAllForUser(int userId) {
        String pattern = UNREAD_PREFIX + userId + ":*";
        java.util.Set<String> keys = redis.keys(pattern);
        java.util.Map<String, Long> counts = new java.util.HashMap<>();
        if (keys == null || keys.isEmpty()) return counts;

        for (String key : keys) {
            String val = redis.opsForValue().get(key);
            if (val != null) {
                try {
                    long count = Long.parseLong(val);
                    if (count > 0) {
                        /*
                         * Strip the "unread:{userId}:" prefix to get just the roomId.
                         * The map key is the roomId so the frontend can match it to sidebar items.
                         */
                        String roomId = key.substring((UNREAD_PREFIX + userId + ":").length());
                        counts.put(roomId, count);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return counts;
    }

    private String buildKey(int userId, String roomId) {
        return UNREAD_PREFIX + userId + ":" + roomId;
    }
}
