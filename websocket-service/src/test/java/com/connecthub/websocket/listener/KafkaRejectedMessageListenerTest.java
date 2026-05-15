package com.connecthub.websocket.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaRejectedMessageListenerTest {

    @Mock SimpMessagingTemplate messaging;
    @Mock ObjectMapper objectMapper;

    @InjectMocks KafkaRejectedMessageListener listener;

    @Test
    void processRejectedMessage_forwardsToUser() throws Exception {
        String json = "{\"senderId\":42,\"reason\":\"RATE_LIMITED\"}";
        Map<String, Object> payload = new HashMap<>();
        payload.put("senderId", 42);
        payload.put("reason", "RATE_LIMITED");

        when(objectMapper.readValue(eq(json), eq(Map.class))).thenReturn(payload);
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(payload);

        listener.processRejectedMessage(json, "chat.messages.rejected", 0L);

        verify(messaging).convertAndSendToUser(eq("42"), eq("/queue/errors"), eq(payload));
    }

    @Test
    void processRejectedMessage_nullSenderId_dropsMessage() throws Exception {
        String json = "{\"reason\":\"RATE_LIMITED\"}";
        Map<String, Object> payload = new HashMap<>();
        payload.put("reason", "RATE_LIMITED");

        when(objectMapper.readValue(eq(json), eq(Map.class))).thenReturn(payload);
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(payload);

        listener.processRejectedMessage(json, "chat.messages.rejected", 0L);

        verify(messaging, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void handleDlq_logsWithoutThrowing() {
        listener.handleDlq("{}", "chat.messages.rejected.dlq", 0L);
    }
}
