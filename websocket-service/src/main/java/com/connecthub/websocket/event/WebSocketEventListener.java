package com.connecthub.websocket.event;

import com.connecthub.websocket.dto.PresenceUpdatePayload;
import com.connecthub.websocket.service.DeliveryService;
import com.connecthub.websocket.service.PresenceNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * WebSocketEventListener — Handles STOMP Session Connect and Disconnect Events
 *
 * PURPOSE:
 * Reacts to WebSocket lifecycle events: user connection and disconnection.
 * On connect, it marks the user as online and delivers any messages they missed
 * while offline. On disconnect, it marks them offline so subsequent messages
 * get queued for offline delivery instead of being silently dropped.
 *
 * HOW SPRING STOMP EVENTS WORK:
 * Spring fires SessionConnectedEvent when a STOMP CONNECTED frame is sent to
 * the
 * client (after the handshake completes). SessionDisconnectEvent fires when the
 * WebSocket connection closes, either cleanly (browser tab closed) or uncleanly
 * (network drop). The StompHeaderAccessor gives access to the session metadata
 * including the authenticated user principal set by the STOMP handshake
 * interceptor.
 *
 * ON CONNECT FLOW:
 * 1. Extract the userId from the STOMP session's user principal (set during JWT
 * authentication in the STOMP handshake interceptor).
 * 2. Call PresenceNotificationService.markOnline() to update the
 * presence-service
 * and Redis online set.
 * 3. Publish a presence event to the "chat:presence" Redis channel so all
 * websocket
 * pods broadcast the ONLINE status update to subscribed clients.
 * 4. Call DeliveryService.flushPendingMessagesWithDelay() — waits 800ms then
 * drains
 * the Redis pending message queue for this user. The delay allows the STOMP
 * session
 * to fully register in Spring's user destination registry before we try to
 * deliver
 * to /user/{id}/queue/messages. Without the delay, messages might be sent to a
 * session that isn't yet mapped and would be silently dropped.
 *
 * ON DISCONNECT FLOW:
 * 1. Extract the userId from the session principal.
 * 2. Call PresenceNotificationService.markOffline() to remove the user from the
 * Redis online set and notify presence-service.
 * 3. Publish an OFFLINE presence event so room members see the online dot
 * disappear.
 *
 * PRESENCE PUBLISHING:
 * publishPresence() publishes a PresenceUpdatePayload JSON to the
 * "chat:presence"
 * Redis channel. All websocket-service pods subscribe to this channel via
 * RedisMessageSubscriber and forward the update to /topic/presence. Frontend
 * clients
 * subscribed to /topic/presence update the online indicator for the affected
 * user.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketEventListener {

    private static final String WS_USER_SESSIONS_PREFIX = "ws:user:sessions:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final PresenceNotificationService presenceNotificationService;
    private final DeliveryService deliveryService;

    /**
     * onConnect — fired when a user's STOMP session is established.
     *
     * HOW IT WORKS:
     * Skips processing if the session has no authenticated user principal (e.g.,
     * unauthenticated handshakes that slipped through). For authenticated sessions,
     * marks the user online, broadcasts their presence, and schedules offline
     * message
     * flushing after the 800ms delay.
     */
    @EventListener
    public void onConnect(SessionConnectedEvent event) {
        StompHeaderAccessor a = StompHeaderAccessor.wrap(event.getMessage());
        if (a.getUser() != null) {
            String uid = a.getUser().getName();
            String sessionId = a.getSessionId();
            log.info("WS connected: user={} session={}", uid, sessionId);

            boolean firstActiveSession = registerUserSession(uid, sessionId);

            if (firstActiveSession) {
                presenceNotificationService.markOnline(uid, sessionId);
                publishPresence(Integer.parseInt(uid), "ONLINE");
            }

            presenceNotificationService.broadcastOnlineSnapshot(uid);

            /*
             * Flush pending messages with an 800ms delay to allow the STOMP session
             * to fully register in Spring's user destination resolver before we try
             * to deliver to /user/{id}/queue/messages. DeliveryService is a separate
             * Spring bean, so @Async applies correctly (self-injection is not needed).
             */
            deliveryService.flushPendingMessagesWithDelay(uid);
        }
    }

    /**
     * onDisconnect — fired when a user's WebSocket connection closes.
     *
     * HOW IT WORKS:
     * Marks the user offline in the presence system and broadcasts the OFFLINE
     * status update to all clients. This fires for both clean disconnects (user
     * closes the tab) and unclean disconnects (network failure, server restart).
     */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor a = StompHeaderAccessor.wrap(event.getMessage());
        if (a.getUser() != null) {
            String uid = a.getUser().getName();
            String sessionId = a.getSessionId();
            log.info("WS disconnected: user={} session={}", uid, sessionId);

            boolean shouldMarkOffline = unregisterUserSession(uid, sessionId);
            if (shouldMarkOffline) {
                presenceNotificationService.markOffline(uid);
                publishPresence(Integer.parseInt(uid), "OFFLINE");
            }
        }
    }

    private boolean registerUserSession(String uid, String sessionId) {
        if (sessionId == null || sessionId.isBlank())
            return true;
        Long size = redis.opsForSet().add(WS_USER_SESSIONS_PREFIX + uid, sessionId);
        return size != null && size == 1L;
    }

    private boolean unregisterUserSession(String uid, String sessionId) {
        String key = WS_USER_SESSIONS_PREFIX + uid;

        if (sessionId != null && !sessionId.isBlank()) {
            redis.opsForSet().remove(key, sessionId);
        }

        Long remaining = redis.opsForSet().size(key);
        boolean shouldMarkOffline = remaining == null || remaining == 0L;
        if (shouldMarkOffline) {
            redis.delete(key);
        }
        return shouldMarkOffline;
    }

    /**
     * publishPresence — broadcasts a presence status change via Redis pub/sub.
     * Serializes a PresenceUpdatePayload to JSON and publishes to "chat:presence".
     * All websocket-service pods receive this and forward it to /topic/presence,
     * where frontend clients update the online/offline dot for the affected user.
     * Failures are caught and logged without interrupting the connect/disconnect
     * flow.
     */
    private void publishPresence(int userId, String status) {
        try {
            PresenceUpdatePayload p = new PresenceUpdatePayload();
            p.setUserId(userId);
            p.setStatus(status);
            redis.convertAndSend("chat:presence", mapper.writeValueAsString(p));
        } catch (Exception e) {
            log.error("Presence publish failed", e);
        }
    }
}
