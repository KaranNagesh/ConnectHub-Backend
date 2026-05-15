package com.connecthub.websocket.service;

import com.connecthub.websocket.dto.ChatMessagePayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * MessagePersistenceService — Async Kafka-Based Message Persistence (Fallback Path)
 *
 * PURPOSE:
 *   This service handles the Kafka side of message persistence. It publishes
 *   message payloads to a Kafka topic where message-service consumes them and
 *   saves to MySQL.
 *
 *   IMPORTANT: This is the FALLBACK persistence path, not the primary one.
 *   The primary path is the synchronous Feign call in ChatWebSocketHandler.
 *   This Kafka path serves two roles:
 *     1. Fallback: if the Feign call to message-service fails (service down,
 *        timeout, etc.), the message is still published here so message-service
 *        can consume and persist it when it recovers.
 *     2. Audit / downstream consumers: analytics services, notification-service,
 *        and search indexers subscribe to "chat.messages.inbound" to process
 *        new messages asynchronously without coupling to the HTTP request path.
 *
 * IDEMPOTENCY:
 *   Message-service's KafkaMessageListener uses the messageId field to detect
 *   duplicates (upsert-style). If the Feign call succeeded AND the Kafka message
 *   is consumed later, the second write is a no-op because the messageId already
 *   exists in the database.
 *
 * KAFKA TOPICS:
 *   "chat.messages.inbound"  — each chat message payload (JSON string)
 *   "room.updates.timestamp" — a lightweight event with roomId + lastMessageAt
 *     used by room-service to update the room's lastMessageAt column.
 *
 * WHY A JSON STRING RATHER THAN AN OBJECT:
 *   kafkaTemplate.send() is typed as KafkaTemplate<String, Object>. We serialize
 *   the payload to a JSON string ourselves so the consumer receives a raw string
 *   it can deserialize with full control, rather than relying on Kafka's
 *   default serialization which can cause class-not-found issues across services.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessagePersistenceService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * persistMessage — publishes a chat message to the Kafka inbound topic.
     * Extracts only the fields message-service needs for persistence, dropping
     * websocket-specific fields like timestamp (which is the broadcast time, not sentAt).
     * On any exception, logs and swallows the error so a Kafka failure never
     * blocks the WebSocket broadcast.
     */
    public boolean persistMessage(ChatMessagePayload p) {
        try {
            Map<String, Object> body = new HashMap<>();
            if (p.getMessageId() != null) body.put("messageId", p.getMessageId());
            body.put("roomId", p.getRoomId());
            body.put("senderId", p.getSenderId());
            body.put("senderUsername", p.getSenderUsername() != null ? p.getSenderUsername() : "");
            if (p.getSubscriptionTier() != null && !p.getSubscriptionTier().isBlank()) {
                body.put("subscriptionTier", p.getSubscriptionTier());
            }
            body.put("content", p.getContent() != null ? p.getContent() : "");
            body.put("type", p.getType() != null ? p.getType() : "TEXT");
            if (p.getMediaUrl() != null) body.put("mediaUrl", p.getMediaUrl());
            if (p.getThumbnailUrl() != null) body.put("thumbnailUrl", p.getThumbnailUrl());
            if (p.getReplyToMessageId() != null) body.put("replyToMessageId", p.getReplyToMessageId());

            String json = objectMapper.writeValueAsString(body);
            kafkaTemplate.send("chat.messages.inbound", json);
            log.debug("Message published to Kafka for room {}", p.getRoomId());
            return true;
        } catch (Exception e) {
            log.error("Failed to publish message to Kafka: {}", e.getMessage());
            return false;
        }
    }

    /**
     * updateRoomTimestamp — publishes a lightweight event to update a room's
     * lastMessageAt timestamp asynchronously via Kafka.
     * Room-service consumes this event and updates the room record in MySQL.
     * This is used as a secondary/fallback alongside the direct Feign call
     * in DeliveryService.updateRoomTimestamp().
     */
    public void updateRoomTimestamp(String roomId) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("roomId", roomId);
            event.put("lastMessageAt", System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send("room.updates.timestamp", json);
        } catch (Exception e) {
            log.debug("Failed to publish room timestamp update: {}", e.getMessage());
        }
    }
}
