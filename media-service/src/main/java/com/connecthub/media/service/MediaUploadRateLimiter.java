package com.connecthub.media.service;

import com.connecthub.media.config.MediaTierLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class MediaUploadRateLimiter {

    private final StringRedisTemplate redisTemplate;

    public boolean tryAcquire(String userId, String subscriptionTier) {
        int limit = MediaTierLimits.uploadsPerMinute(subscriptionTier);
        long minute = Instant.now().getEpochSecond() / 60;
        String key = "ratelimit:" + userId + ":uploads:" + minute;

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
