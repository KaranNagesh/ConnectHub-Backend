package com.connecthub.websocket.service;

import com.connecthub.websocket.client.PresenceServiceClient;
import com.connecthub.websocket.dto.PresenceUpdatePayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PresenceNotificationService {

    private final PresenceServiceClient presenceServiceClient;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    @Async("asyncExecutor")
    public void markOnline(String uid, String sessionId) {
        try {
            presenceServiceClient.markOnline(uid,
                    Map.of("deviceType", "WEB", "sessionId", sessionId != null ? sessionId : ""), uid);
            log.debug("Presence ONLINE notified for user {}", uid);
        } catch (Exception e) {
            log.warn("Could not notify presence-service ONLINE for user {}: {}", uid, e.getMessage());
        }
    }

    @Async("asyncExecutor")
    public void markOffline(String uid) {
        try {
            presenceServiceClient.markOffline(uid, uid);
            log.debug("Presence OFFLINE notified for user {}", uid);
        } catch (Exception e) {
            log.warn("Could not notify presence-service OFFLINE for user {}: {}", uid, e.getMessage());
        }
    }

    /**
     * Fetches all currently online user IDs and re-publishes their ONLINE status to
     * the chat:presence Redis channel. Called on new user connect so the joining user
     * receives a snapshot of who is already online (events they missed before subscribing).
     */
    @Async("asyncExecutor")
    public void broadcastOnlineSnapshot(String excludeUid) {
        try {
            List<Integer> onlineIds = presenceServiceClient.getOnlineUserIds(excludeUid);
            for (Integer id : onlineIds) {
                if (String.valueOf(id).equals(excludeUid)) continue;
                PresenceUpdatePayload p = new PresenceUpdatePayload();
                p.setUserId(id);
                p.setStatus("ONLINE");
                redis.convertAndSend("chat:presence", mapper.writeValueAsString(p));
            }
            log.debug("Broadcast online snapshot ({} users) to newly connected user {}", onlineIds.size(), excludeUid);
        } catch (Exception e) {
            log.warn("Could not broadcast online snapshot for user {}: {}", excludeUid, e.getMessage());
        }
    }
}
