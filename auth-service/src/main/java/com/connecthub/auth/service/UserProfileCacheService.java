package com.connecthub.auth.service;

import com.connecthub.auth.dto.UserProfileDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * UserProfileCacheService — Redis Cache for User Profile Data
 *
 * PURPOSE:
 *   User profiles (name, avatar, username, role, etc.) are read very frequently
 *   by multiple services — especially room-service, which enriches every room's
 *   member list with profile data using Feign HTTP calls to auth-service.
 *   Without a cache, every room page load would trigger multiple database reads.
 *
 *   This service caches serialized UserProfileDto objects in Redis with a 30-minute
 *   TTL, so repeated lookups within that window hit Redis (fast, in-memory) instead
 *   of the MySQL database (slower, involves query parsing and disk I/O).
 *
 * REDIS KEY STRUCTURE:
 *   "user:profile:{userId}"   (e.g., "user:profile:42")
 *   Value: JSON-serialized UserProfileDto string
 *   TTL:   30 minutes
 *
 * CACHE INVALIDATION STRATEGY:
 *   The cache is invalidated (evicted) whenever the profile data might have changed.
 *   evict() is called from AuthServiceImpl after:
 *     - updateProfile()       — name, username, avatar, bio changed
 *     - updateStatus()        — ONLINE/AWAY/DND status changed
 *     - suspendUser()         — account became inactive
 *     - reactivateUser()      — account became active again
 *   Without this, a user who changes their name would still see the old name
 *   reflected in other users' room member lists for up to 30 minutes.
 *
 * WHY JSON SERIALIZATION:
 *   Redis stores strings. Jackson ObjectMapper serializes the UserProfileDto
 *   to a JSON string for storage and deserializes it back on retrieval.
 *   If deserialization fails (e.g., the DTO schema changed after deployment),
 *   the corrupted cache entry is evicted and a fresh DB read is triggered
 *   (graceful degradation rather than throwing an error to the caller).
 *
 * THREAD SAFETY:
 *   StringRedisTemplate is thread-safe. ObjectMapper is thread-safe when configured
 *   without mutable state. Both are injected as Spring singletons so this service
 *   can safely be called from multiple threads simultaneously.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileCacheService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private static final String PROFILE_KEY_PREFIX  = "user:profile:";
    private static final long   PROFILE_TTL_MINUTES = 30;

    /**
     * getCachedProfile — attempts to read a user profile from Redis.
     * Returns null on a cache miss so the caller knows to load from the database.
     * If the cached JSON is malformed (schema mismatch after deployment), the
     * corrupt entry is evicted and null is returned rather than throwing an exception.
     */
    public UserProfileDto getCachedProfile(int userId) {
        String key  = PROFILE_KEY_PREFIX + userId;
        String json;
        try {
            json = redis.opsForValue().get(key);
        } catch (DataAccessException e) {
            log.warn("Profile cache unavailable while reading user {}: {}", userId, e.getMessage());
            return null;
        }
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, UserProfileDto.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize cached profile for user {}: {}", userId, e.getMessage());
            evict(userId);
            return null;
        }
    }

    /**
     * cacheProfile — serializes a UserProfileDto to JSON and stores it in Redis.
     * If serialization fails (very unlikely with a well-defined DTO), we log a
     * warning and silently skip caching rather than failing the caller's operation.
     */
    public void cacheProfile(int userId, UserProfileDto profile) {
        try {
            String key  = PROFILE_KEY_PREFIX + userId;
            String json = objectMapper.writeValueAsString(profile);
            redis.opsForValue().set(key, json, PROFILE_TTL_MINUTES, TimeUnit.MINUTES);
            log.debug("Cached profile for user {}", userId);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize profile for user {}: {}", userId, e.getMessage());
        } catch (DataAccessException e) {
            log.warn("Profile cache unavailable while caching user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * evict — removes a user's profile from the cache.
     * Called after any mutation that changes the profile (name, status, avatar, etc.)
     * so subsequent reads will reload the fresh data from MySQL.
     */
    public void evict(int userId) {
        try {
            redis.delete(PROFILE_KEY_PREFIX + userId);
            log.debug("Evicted profile cache for user {}", userId);
        } catch (DataAccessException e) {
            log.warn("Profile cache unavailable while evicting user {}: {}", userId, e.getMessage());
        }
    }
}
