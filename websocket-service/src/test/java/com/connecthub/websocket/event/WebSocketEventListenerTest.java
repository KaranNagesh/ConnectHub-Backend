package com.connecthub.websocket.event;

import com.connecthub.websocket.service.PresenceNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketEventListenerTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ObjectMapper mapper;
    @Mock
    private PresenceNotificationService presenceNotificationService;
    @Mock
    private com.connecthub.websocket.service.DeliveryService deliveryService;
    @Mock
    private SetOperations<String, String> setOps;
    @InjectMocks
    private WebSocketEventListener listener;

    private final Principal principal = () -> "7";

    private static final String SESSION_SET_KEY = "ws:user:sessions:7";

    private SessionConnectedEvent buildConnectEvent(Principal user) {
        StompHeaderAccessor a = StompHeaderAccessor.create(StompCommand.CONNECT);
        a.setUser(user);
        a.setSessionId("sess-abc");
        Message<byte[]> msg = MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
        return new SessionConnectedEvent(this, msg);
    }

    private SessionDisconnectEvent buildDisconnectEvent(Principal user) {
        StompHeaderAccessor a = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        a.setUser(user);
        Message<byte[]> msg = MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
        return new SessionDisconnectEvent(this, msg, "sess-abc", null);
    }

    @Test
    void onConnect_publishesOnlinePresenceViaRedis() throws Exception {
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.add(eq(SESSION_SET_KEY), eq("sess-abc"))).thenReturn(1L);
        when(mapper.writeValueAsString(any())).thenReturn("{\"userId\":7,\"status\":\"ONLINE\"}");
        listener.onConnect(buildConnectEvent(principal));
        verify(redis).convertAndSend(eq("chat:presence"), anyString());
    }

    @Test
    void onConnect_notifiesPresenceServiceOnline() throws Exception {
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.add(eq(SESSION_SET_KEY), eq("sess-abc"))).thenReturn(1L);
        when(mapper.writeValueAsString(any())).thenReturn("{}");
        listener.onConnect(buildConnectEvent(principal));
        verify(presenceNotificationService).markOnline(eq("7"), eq("sess-abc"));
    }

    @Test
    void onConnect_secondSession_doesNotNotifyOnlineAgain() {
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.add(eq(SESSION_SET_KEY), eq("sess-abc"))).thenReturn(2L);

        listener.onConnect(buildConnectEvent(principal));

        verify(presenceNotificationService, never()).markOnline(anyString(), anyString());
        verify(redis, never()).convertAndSend(eq("chat:presence"), anyString());
    }

    @Test
    void onConnect_nullPrincipal_noInteractions() {
        listener.onConnect(buildConnectEvent(null));
        verifyNoInteractions(redis, presenceNotificationService);
    }

    @Test
    void onDisconnect_publishesOfflinePresenceViaRedis() throws Exception {
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.size(eq(SESSION_SET_KEY))).thenReturn(0L);
        when(mapper.writeValueAsString(any())).thenReturn("{\"userId\":7,\"status\":\"OFFLINE\"}");
        listener.onDisconnect(buildDisconnectEvent(principal));
        verify(redis).convertAndSend(eq("chat:presence"), anyString());
    }

    @Test
    void onDisconnect_notifiesPresenceServiceOffline() throws Exception {
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.size(eq(SESSION_SET_KEY))).thenReturn(0L);
        when(mapper.writeValueAsString(any())).thenReturn("{}");
        listener.onDisconnect(buildDisconnectEvent(principal));
        verify(presenceNotificationService).markOffline(eq("7"));
    }

    @Test
    void onDisconnect_withRemainingSessions_doesNotMarkOffline() {
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.size(eq(SESSION_SET_KEY))).thenReturn(1L);

        listener.onDisconnect(buildDisconnectEvent(principal));

        verify(presenceNotificationService, never()).markOffline(anyString());
        verify(redis, never()).convertAndSend(eq("chat:presence"), anyString());
        verify(redis, never()).delete(eq(SESSION_SET_KEY));
    }

    @Test
    void onDisconnect_nullPrincipal_noInteractions() {
        listener.onDisconnect(buildDisconnectEvent(null));
        verifyNoInteractions(redis, presenceNotificationService);
    }
    //
    // @Test
    // void notifyPresenceService_restFailure_doesNotThrow() {
    // doThrow(new
    // RuntimeException("timeout")).when(presenceServiceClient).markOnline(eq("7"),
    // any());
    // assertDoesNotThrow(() -> listener.notifyPresenceService("7", "ONLINE",
    // "s1"));
    // }
    //
    // @Test
    // void notifyPresenceServiceOffline_restFailure_doesNotThrow() {
    // doThrow(new
    // RuntimeException("timeout")).when(presenceServiceClient).markOffline(eq("7"));
    // assertDoesNotThrow(() -> listener.notifyPresenceServiceOffline("7"));
    // }
}
