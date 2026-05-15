package com.connecthub.websocket.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomNotificationServiceTest {

    @Mock
    private SimpMessagingTemplate messaging;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private RoomNotificationService roomNotificationService;

    @Test
    void processRoomCreated_sendsToAllMembers() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("memberIds", List.of(1, 2));

        when(objectMapper.readValue("{}", Map.class)).thenReturn(payload);
        when(objectMapper.convertValue(payload, Map.class)).thenReturn(payload);

        roomNotificationService.processRoomCreated("{}", "room.created", 0);

        verify(messaging).convertAndSendToUser(eq("1"), eq("/queue/notifications"), any(Map.class));
        verify(messaging).convertAndSendToUser(eq("2"), eq("/queue/notifications"), any(Map.class));
    }

    @Test
    void processRoomCreated_invalidPayload_aborts() throws Exception {
        when(objectMapper.readValue("inv", Map.class)).thenThrow(new RuntimeException("err"));

        roomNotificationService.processRoomCreated("inv", "room.created", 0);

        verify(messaging, never()).convertAndSendToUser(anyString(), anyString(), any(Map.class));
    }
}
