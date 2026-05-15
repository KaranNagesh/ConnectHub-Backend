package com.connecthub.room.service;

import com.connecthub.room.entity.RoomMember;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * RoomCacheService — Redis Cache for Room Member Lists
 *
 * PURPOSE:
 *   Caches the list of members for each room in Redis to avoid repeated database
 *   queries. This cache is critical for performance because getMembers() is called
 *   on every single message delivery — websocket-service uses Feign to ask room-service
 *   "who is in this room?" before delivering to each member. Without caching, a room
 *   with active messaging would generate dozens of identical DB queries per minute.
 *
 * CACHE KEY AND TTL:
 *   Key:  "room:members:{roomId}"   (e.g., "room:members:room_abc123")
 *   TTL:  5 minutes
 *   If no messages arrive and no mutations happen for 5 minutes, the cache entry
 *   auto-expires. The next getMembers() call will be a cache miss and reload from DB.
 *
 * CACHE INVALIDATION STRATEGY:
 *   The cache is evicted (deleted) on any mutation that changes the member list:
 *   - addMember(): new member joined the room
 *   - removeMember(): member left or was removed
 *   - updateRole(): a member's role changed (MEMBER ↔ ADMIN)
 *   After eviction, the next call to getCachedMembers() returns null (cache miss),
 *   causing RoomService.getMembers() to reload the fresh list from the DB and re-cache it.
 *   This is the cache-aside (lazy loading) invalidation pattern.
 *
 * ERROR HANDLING:
 *   If the cached JSON is corrupted (e.g., partial write, schema change), deserialization
 *   fails and the corrupted entry is automatically evicted so the next request gets a
 *   fresh DB load. This prevents a bad cache entry from permanently breaking member delivery.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoomCacheService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private static final String MEMBER_KEY_PREFIX = "room:members:";
    private static final long   MEMBER_TTL_MINUTES = 5;

    /**
     * getCachedMembers — returns the cached member list for a room, or null on cache miss.
     *
     * HOW IT WORKS:
     *   Reads the JSON string from Redis and deserializes it into a List<RoomMember>.
     *   Returns null (not an empty list) to distinguish between "no cache entry" and
     *   "room has no members". The caller (RoomService.getMembers) treats null as a
     *   cache miss and fetches from the database.
     *   If deserialization fails (corrupted JSON), the entry is evicted and null is returned.
     */
    public List<RoomMember> getCachedMembers(String roomId) {
        String key  = MEMBER_KEY_PREFIX + roomId;
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<RoomMember>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize cached members for room {}: {}", roomId, e.getMessage());
            evict(roomId);
            return null;
        }
    }

    /**
     * cacheMembers — serializes and stores the member list in Redis with a 5-minute TTL.
     * Called by RoomService.getMembers() after a cache miss to populate the cache for
     * subsequent requests. Serialization failures are logged but do not throw — a failed
     * cache write just means the next request will be another cache miss.
     */
    public void cacheMembers(String roomId, List<RoomMember> members) {
        try {
            String key  = MEMBER_KEY_PREFIX + roomId;
            String json = objectMapper.writeValueAsString(members);
            redis.opsForValue().set(key, json, MEMBER_TTL_MINUTES, TimeUnit.MINUTES);
            log.debug("Cached {} members for room {}", members.size(), roomId);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize members for room {}: {}", roomId, e.getMessage());
        }
    }

    /**
     * evict — deletes the cached member list for a room.
     * Called immediately after any membership mutation so the next getMembers() call
     * reads the authoritative data from the database instead of stale cache.
     */
    public void evict(String roomId) {
        redis.delete(MEMBER_KEY_PREFIX + roomId);
        log.debug("Evicted member cache for room {}", roomId);
    }
}
