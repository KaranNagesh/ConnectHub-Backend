package com.connecthub.message.service;

import com.connecthub.message.config.SubscriptionTierLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * MessageTierRateLimiter — Per-User Per-Minute Message Rate Limiter
 *
 * PURPOSE:
 *   Enforces a cap on how many chat messages a user can send per minute based on
 *   their subscription tier. FREE users have a lower cap; PRO users get a higher one.
 *   This prevents spam and ensures fair resource usage across all users on shared
 *   infrastructure. The limits are defined in SubscriptionTierLimits.
 *
 * WHERE IT IS CALLED:
 *   MessageService.send() calls tryAcquire() before persisting any message. This
 *   covers both entry paths: direct REST calls and Kafka-consumed messages from
 *   websocket-service. The gateway's RateLimitFilter also enforces a global per-minute
 *   cap, but this service provides a finer-grained, message-specific check.
 *
 * HOW IT WORKS — Redis Sliding-Window Counter:
 *   The rate limit is implemented with a Redis counter keyed by user + time bucket.
 *   The time bucket is the current Unix epoch second divided by 60, which gives a
 *   monotonically incrementing "minute number". Each (userId, minute) pair gets its
 *   own Redis key:
 *
 *     Key: "ratelimit:{userId}:messages:{minuteNumber}"
 *
 *   INCR atomically increments the counter. On the first increment (count == 1),
 *   the key is given a 2-minute TTL so it auto-expires once the window passes.
 *   If the incremented count exceeds the tier's limit, the counter is decremented
 *   back (undoing the increment) and the method returns false.
 *
 * WHY DECREMENT ON REJECT:
 *   Without the decrement, an attacker could make requests past the limit and keep
 *   the counter inflated, pushing legitimate requests out even in the next minute
 *   (since the TTL is 2 minutes). Decrementing on rejection means the counter only
 *   counts accepted requests, keeping the window accurate.
 *
 * WHY 2-MINUTE TTL:
 *   The key represents a 1-minute bucket. We set TTL to 2 minutes (not 1) to ensure
 *   the key is still alive if a request arrives right at the minute boundary while
 *   Redis hasn't fully expired the previous key yet. This gives a small buffer and
 *   avoids premature expiry edge cases.
 *
 * ATOMICITY:
 *   Redis INCR and DECR are single atomic commands. There are no race conditions
 *   between the increment and the limit check — each operation is processed by
 *   Redis's single-threaded command processor in order.
 */
@Service
@RequiredArgsConstructor
public class MessageTierRateLimiter {

    private final StringRedisTemplate redisTemplate;

    /**
     * tryAcquire — attempts to acquire a send slot for the given user and tier.
     *
     * HOW IT WORKS:
     *   1. Look up the per-minute limit for the subscription tier.
     *   2. Compute the current minute bucket (epochSecond / 60).
     *   3. INCR the Redis counter for this user+minute combination.
     *   4. On the first increment, set a 2-minute TTL on the key.
     *   5. If the count exceeds the limit, DECR to undo and return false.
     *   6. Otherwise return true — the request is allowed.
     *
     * @param userId           the user's ID as a string (Redis key component)
     * @param subscriptionTier normalized tier string ("FREE" or "PRO")
     * @return true if the request is within the rate limit; false if the cap is exceeded
     */
    public boolean tryAcquire(String userId, String subscriptionTier) {
        int limit = SubscriptionTierLimits.messagesPerMinute(subscriptionTier);
        long minute = Instant.now().getEpochSecond() / 60;
        String key = "ratelimit:" + userId + ":messages:" + minute;

        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofMinutes(2));
        }
        if (count != null && count > limit) {
            redisTemplate.opsForValue().decrement(key);
            return false;
        }
        return true;
    }
}
