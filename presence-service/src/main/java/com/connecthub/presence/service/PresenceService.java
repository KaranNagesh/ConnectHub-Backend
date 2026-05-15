package com.connecthub.presence.service;

import com.connecthub.presence.dto.UserPresence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * PresenceService — Real-Time User Online/Offline Tracking via Redis
 *
 * PURPOSE:
 *   Tracks which users are currently online and their last-seen time. This data
 *   powers the green online dot shown next to user avatars in the chat sidebar.
 *   Presence state is stored entirely in Redis (no database) because it is
 *   ephemeral — it doesn't need to survive a Redis restart and must be fast.
 *
 * HOW PRESENCE IS TRACKED:
 *   Two Redis structures work together:
 *
 *   1. Per-user detail hash — Key: "presence:{userId}"
 *      Stores a JSON-serialized UserPresence object containing: userId, status
 *      (ONLINE/AWAY/etc.), deviceType, sessionId, connectedAt, lastPingAt.
 *      Each key has a 5-minute TTL (auto-expire if the user goes silent).
 *
 *   2. Online set — Key: "presence:online"
 *      A Redis Set containing the string IDs of all currently online users.
 *      Used for fast O(1) membership checks: "is user 42 online right now?"
 *      The DeliveryService uses this set to decide whether to queue a message
 *      for offline delivery or not.
 *
 * HEARTBEAT / PING FLOW:
 *   The frontend sends periodic heartbeat frames every ~30 seconds. Each ping
 *   calls ping() which refreshes the lastPingAt timestamp and resets the 5-minute
 *   TTL on the presence key. If a user stops sending pings (tab closed, network
 *   dropped), the key expires automatically after 5 minutes without any explicit
 *   disconnect event needed.
 *
 * STALE SESSION CLEANUP (@Scheduled):
 *   cleanStale() runs every 60 seconds and scans all members of the "presence:online"
 *   set. Any user whose lastPingAt is older than 90 seconds is considered disconnected
 *   and is removed from the online set. This 90-second threshold gives a buffer above
 *   the 30-second ping interval to account for occasional latency spikes.
 *
 * WHY THE ONLINE SET AND PER-USER KEY BOTH EXIST:
 *   - The set gives O(1) presence checks and supports SMEMBERS for bulk lookups.
 *   - The per-user key stores the full detail (deviceType, sessionId, timestamps)
 *     which is needed for the presence detail endpoint and for stale cleanup.
 *   Without the set, bulk presence checks for a room's members would require
 *   N individual key lookups; the set makes it a single SISMEMBER call per user.
 *
 * JACKSON CONFIGURATION:
 *   JavaTimeModule is manually registered on the ObjectMapper because UserPresence
 *   uses LocalDateTime fields. Without this module, Jackson cannot serialize or
 *   deserialize Java 8 date/time types and throws an exception.
 */
@Service @Slf4j
public class PresenceService {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private static final String PREFIX = "presence:";
    private static final String ONLINE_SET = "presence:online";

    /*
     * 5-minute TTL on the per-user presence key. If the user stops sending pings,
     * their presence record expires automatically without needing a disconnect event.
     */
    private static final Duration TTL = Duration.ofMinutes(5);

    public PresenceService(StringRedisTemplate redis) {
        this.redis = redis;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    /**
     * setOnline — marks a user as online when they connect their WebSocket session.
     *
     * HOW IT WORKS:
     *   Creates a UserPresence record with status=ONLINE, the current time as both
     *   connectedAt and lastPingAt, and the device/session info from the connect frame.
     *   The record is saved to the per-user Redis key with a 5-minute TTL, and the
     *   user's ID is added to the "presence:online" set for fast membership checks.
     *
     * Called by: websocket-service WebSocketEventListener on STOMP CONNECT event.
     */
    public void setOnline(int userId, String deviceType, String sessionId) {
        UserPresence p = UserPresence.builder().userId(userId).status("ONLINE").deviceType(deviceType)
                .sessionId(sessionId).connectedAt(LocalDateTime.now()).lastPingAt(LocalDateTime.now()).build();
        save(userId, p);
        redis.opsForSet().add(ONLINE_SET, String.valueOf(userId));
        log.info("User {} ONLINE (session={})", userId, sessionId);
    }

    /**
     * setOffline — marks a user as offline when their WebSocket session disconnects.
     *
     * HOW IT WORKS:
     *   Deletes the per-user presence key entirely and removes the user from the
     *   "presence:online" set. After this call, isOnline() returns false and
     *   DeliveryService will start queuing messages for offline delivery.
     *
     * Called by: websocket-service WebSocketEventListener on STOMP DISCONNECT event,
     * and by cleanStale() for sessions that stopped pinging.
     */
    public void setOffline(int userId) {
        redis.delete(PREFIX + userId);
        redis.opsForSet().remove(ONLINE_SET, String.valueOf(userId));
        log.info("User {} OFFLINE", userId);
    }

    /**
     * updateStatus — updates the user's custom status message and status label.
     * Status labels can be: ONLINE, AWAY, DO_NOT_DISTURB, etc. The custom message
     * is free-text set by the user (e.g., "In a meeting"). Uses ifPresent() to
     * silently ignore updates for users not currently in the online set.
     */
    public void updateStatus(int userId, String status, String msg) {
        get(userId).ifPresent(p -> { p.setStatus(status); p.setCustomMessage(msg); save(userId, p); });
    }

    /**
     * get — returns the full UserPresence record for a single user, if they are online.
     * Reads the JSON from Redis and deserializes it. Returns Optional.empty() if the
     * user is offline (key doesn't exist) or if the JSON is malformed.
     */
    public Optional<UserPresence> get(int userId) {
        String json = redis.opsForValue().get(PREFIX + userId);
        if (json == null) return Optional.empty();
        try { return Optional.of(mapper.readValue(json, UserPresence.class)); }
        catch (JsonProcessingException e) { return Optional.empty(); }
    }

    /**
     * getBulk — returns presence records for a list of user IDs in one Redis call.
     *
     * HOW IT WORKS:
     *   Uses Redis MGET (multiGet) to fetch all presence keys in a single network
     *   round-trip rather than N individual GET calls. Null entries (offline users)
     *   are filtered out. Malformed JSON entries are also filtered silently.
     *   This is used when loading a room's sidebar to show online dots for all members
     *   at once without hammering Redis with individual requests.
     */
    public List<UserPresence> getBulk(List<Integer> ids) {
        List<String> keys = ids.stream().map(i -> PREFIX + i).collect(Collectors.toList());
        List<String> vals = redis.opsForValue().multiGet(keys);
        if (vals == null) return List.of();
        return vals.stream().filter(Objects::nonNull).map(j -> {
            try { return mapper.readValue(j, UserPresence.class); } catch (Exception e) { return null; }
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    /**
     * ping — refreshes the lastPingAt timestamp and resets the 5-minute TTL.
     * Called every ~30 seconds by the frontend's heartbeat mechanism. As long as
     * pings keep arriving, the presence key stays alive and the user remains online.
     * If pings stop, the key expires after 5 minutes and cleanStale() removes the
     * user from the online set at the next scheduled run.
     */
    public void ping(int userId) { get(userId).ifPresent(p -> { p.setLastPingAt(LocalDateTime.now()); save(userId, p); }); }

    /**
     * isOnline — returns true if the user is currently in the "presence:online" Redis set.
     * This is the O(1) presence check used by DeliveryService to decide whether to
     * queue a message for offline delivery or not.
     */
    public boolean isOnline(int userId) { return Boolean.TRUE.equals(redis.opsForSet().isMember(ONLINE_SET, String.valueOf(userId))); }

    /**
     * onlineCount — returns the total number of users currently marked as online.
     * Used for the admin dashboard's real-time active users metric.
     */
    public int onlineCount() { Long s = redis.opsForSet().size(ONLINE_SET); return s != null ? s.intValue() : 0; }

    public List<Integer> getOnlineUserIds() {
        Set<String> members = redis.opsForSet().members(ONLINE_SET);
        if (members == null) return List.of();
        return members.stream().map(Integer::parseInt).collect(Collectors.toList());
    }

    /**
     * cleanStale — scheduled cleanup that removes zombie sessions from the online set.
     *
     * HOW IT WORKS:
     *   Runs every 60 seconds. Iterates over every user ID in the "presence:online" set
     *   and checks their lastPingAt. If the last ping was more than 90 seconds ago,
     *   the session is considered stale (the user's client is no longer sending heartbeats)
     *   and setOffline() is called to clean up both the presence key and the online set.
     *
     * WHY 90 SECONDS:
     *   The frontend pings every ~30 seconds. A 90-second threshold allows for up to
     *   2 missed pings before a session is declared dead, providing resilience against
     *   brief network interruptions without keeping zombie sessions alive indefinitely.
     */
    @Scheduled(fixedRate = 60000)
    public void cleanStale() {
        Set<String> ids = redis.opsForSet().members(ONLINE_SET);
        if (ids == null) return;
        LocalDateTime thresh = LocalDateTime.now().minusSeconds(90);
        for (String idStr : ids) {
            int uid = Integer.parseInt(idStr);
            get(uid).ifPresent(p -> { if (p.getLastPingAt() != null && p.getLastPingAt().isBefore(thresh)) { setOffline(uid); log.info("Cleaned stale session: user {}", uid); } });
        }
    }

    /**
     * save — serializes the UserPresence object to JSON and stores it in Redis with TTL.
     * The 5-minute TTL is reset on every save, so online users with active pings
     * never expire. This is the single write path for all presence mutations.
     */
    private void save(int userId, UserPresence p) {
        try { redis.opsForValue().set(PREFIX + userId, mapper.writeValueAsString(p), TTL); }
        catch (JsonProcessingException e) { log.error("Failed to save presence for {}", userId, e); }
    }
}
