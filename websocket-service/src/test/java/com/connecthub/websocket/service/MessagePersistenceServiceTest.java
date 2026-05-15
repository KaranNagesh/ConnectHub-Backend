package com.connecthub.websocket.service;

import com.connecthub.websocket.dto.ChatMessagePayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessagePersistenceServiceTest {

    @Mock KafkaTemplate<String, Object> kafkaTemplate;
    @Mock ObjectMapper objectMapper;

    @InjectMocks MessagePersistenceService service;

    @Test
    void persistMessage_publishesToKafka() throws Exception {
        ChatMessagePayload p = new ChatMessagePayload();
        p.setMessageId("m1");
        p.setRoomId("room1");
        p.setSenderId(42);
        p.setContent("Hello");
        p.setType("TEXT");

        when(objectMapper.writeValueAsString(any())).thenReturn("{\"roomId\":\"room1\"}");

        assertTrue(service.persistMessage(p));

        verify(kafkaTemplate).send(eq("chat.messages.inbound"), anyString());
    }

    @Test
    void persistMessage_onSerializationError_doesNotThrow() throws Exception {
        ChatMessagePayload p = new ChatMessagePayload();
        p.setRoomId("room1");
        p.setSenderId(1);

        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("err") {});

        assertFalse(service.persistMessage(p));  // must not propagate
    }

    @Test
    void updateRoomTimestamp_publishesToKafka() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"roomId\":\"r1\"}");

        service.updateRoomTimestamp("r1");

        verify(kafkaTemplate).send(eq("room.updates.timestamp"), anyString());
    }
}
