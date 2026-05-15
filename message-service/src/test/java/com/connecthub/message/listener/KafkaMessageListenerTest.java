package com.connecthub.message.listener;

import com.connecthub.message.entity.Message;
import com.connecthub.message.exception.TooManyRequestsException;
import com.connecthub.message.service.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaMessageListenerTest {

    @Mock private MessageService messageService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private ObjectMapper objectMapper;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks
    private KafkaMessageListener listener;

    private final String topic = "chat.messages.inbound";
    private final int partition = 0;
    private final long offset = 1;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void processInboundMessage_invalidPayload_aborts() throws Exception {
        Map<String, Object> payload = Map.of("roomId", "r1"); // missing senderId
        when(objectMapper.readValue(anyString(), eq(Map.class))).thenReturn(payload);
        
        listener.processInboundMessage("{}", topic, partition, offset);
        
        verify(messageService, never()).send(any(), any());
    }

    @Test
    void processInboundMessage_idempotentSkip() throws Exception {
        Map<String, Object> payload = Map.of("roomId", "r1", "senderId", 1, "messageId", "m1");
        when(objectMapper.readValue(anyString(), eq(Map.class))).thenReturn(payload);
        when(messageService.existsById("m1")).thenReturn(true);
        when(objectMapper.writeValueAsString(any())).thenReturn("payload");

        listener.processInboundMessage("{}", topic, partition, offset);
        
        verify(kafkaTemplate).send("chat.messages.outbound", "payload");
        verify(messageService, never()).send(any(), any());
    }

    @Test
    void processInboundMessage_guestLimitExceeded() throws Exception {
        Map<String, Object> payload = Map.of(
            "roomId", "r1", "senderId", 2, "senderUsername", "guest_123"
        );
        when(objectMapper.readValue(anyString(), eq(Map.class))).thenReturn(payload);
        when(valueOps.increment("guest:limits:2")).thenReturn(51L); // GUEST_MESSAGE_LIMIT is 50
        when(objectMapper.writeValueAsString(any())).thenReturn("rej");

        listener.processInboundMessage("{}", topic, partition, offset);
        
        verify(kafkaTemplate).send("chat.messages.rejected", "rej");
        verify(messageService, never()).send(any(), any());
    }

    @Test
    void processInboundMessage_success() throws Exception {
        Map<String, Object> payload = Map.of(
            "roomId", "r1", "senderId", 1, "content", "hi", "subscriptionTier", "PRO"
        );
        when(objectMapper.readValue(anyString(), eq(Map.class))).thenReturn(payload);
        Message saved = new Message();
        when(messageService.send(any(), eq("PRO"))).thenReturn(saved);
        when(objectMapper.writeValueAsString(any())).thenReturn("saved");

        listener.processInboundMessage("{}", topic, partition, offset);
        
        verify(kafkaTemplate).send("chat.messages.outbound", "saved");
    }

    @Test
    void processInboundMessage_rateLimitExceeded() throws Exception {
        Map<String, Object> payload = Map.of(
            "roomId", "r1", "senderId", 1, "subscriptionTier", "FREE"
        );
        when(objectMapper.readValue(anyString(), eq(Map.class))).thenReturn(payload);
        when(messageService.send(any(), eq("FREE"))).thenThrow(new TooManyRequestsException("Cap hit", 5));
        when(objectMapper.writeValueAsString(any())).thenReturn("rate-limit");

        listener.processInboundMessage("{}", topic, partition, offset);
        
        verify(kafkaTemplate).send("chat.messages.rejected", "rate-limit");
        verify(kafkaTemplate, never()).send("chat.messages.outbound", "rate-limit");
    }

    @Test
    void handleDlq() {
        assertDoesNotThrow(() -> listener.handleDlq("{}", "topic", 1));
    }
}
