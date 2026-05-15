package com.connecthub.websocket.service;

import com.connecthub.websocket.client.PresenceServiceClient;
import com.connecthub.websocket.dto.PresenceUpdatePayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PresenceNotificationServiceTest {

    @Mock
    private PresenceServiceClient presenceServiceClient;

    @Mock
    private StringRedisTemplate redis;

    private ObjectMapper mapper;
    private PresenceNotificationService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        service = new PresenceNotificationService(presenceServiceClient, redis, mapper);
    }

    @Test
    void markOnline_callsPresenceServiceClient_withSessionId() {
        service.markOnline("3", "sess-3");

        verify(presenceServiceClient).markOnline(eq("3"),
                eq(java.util.Map.of("deviceType", "WEB", "sessionId", "sess-3")), eq("3"));
    }

    @Test
    void markOnline_nullSessionId_usesEmptyString() {
        service.markOnline("4", null);

        verify(presenceServiceClient).markOnline(eq("4"), eq(java.util.Map.of("deviceType", "WEB", "sessionId", "")),
                eq("4"));
    }

    @Test
    void markOnline_clientFailure_isHandled() {
        doThrow(new RuntimeException("down")).when(presenceServiceClient).markOnline(any(), any(), any());

        assertDoesNotThrow(() -> service.markOnline("5", "s5"));
    }

    @Test
    void markOffline_callsPresenceServiceClient() {
        service.markOffline("7");

        verify(presenceServiceClient).markOffline("7", "7");
    }

    @Test
    void markOffline_clientFailure_isHandled() {
        doThrow(new RuntimeException("down")).when(presenceServiceClient).markOffline(any(), any());

        assertDoesNotThrow(() -> service.markOffline("8"));
    }

    @Test
    void broadcastOnlineSnapshot_publishesOnlineEventsExceptExcludedUser() throws Exception {
        when(presenceServiceClient.getOnlineUserIds("10")).thenReturn(List.of(10, 11));

        service.broadcastOnlineSnapshot("10");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis, times(1)).convertAndSend(eq("chat:presence"), payloadCaptor.capture());

        PresenceUpdatePayload payload = mapper.readValue(payloadCaptor.getValue(), PresenceUpdatePayload.class);
        assertEquals(11, payload.getUserId());
        assertEquals("ONLINE", payload.getStatus());
    }

    @Test
    void broadcastOnlineSnapshot_failure_isHandled() {
        when(presenceServiceClient.getOnlineUserIds("1")).thenThrow(new RuntimeException("presence down"));

        assertDoesNotThrow(() -> service.broadcastOnlineSnapshot("1"));
        verify(redis, never()).convertAndSend(anyString(), anyString());
    }
}
