package com.connecthub.websocket.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoomNotificationService {

    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "room.created",
            groupId = "websocket-service-room-group"
    )
    public void processRoomCreated(
            @Payload String payloadJson,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.debug("Consuming {}@{}", topic, offset);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.convertValue(
                    objectMapper.readValue(payloadJson, Map.class), Map.class);
            
            payload.put("type", "ROOM_CREATED");

            Object memberIdsObj = payload.get("memberIds");
            if (memberIdsObj instanceof List<?> memberIds) {
                for (Object memberIdObj : memberIds) {
                    if (memberIdObj != null) {
                        String memberId = memberIdObj.toString();
                        // Push to /queue/notifications
                        messaging.convertAndSendToUser(memberId, "/queue/notifications", payload);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to process room.created event", e);
        }
    }
}
