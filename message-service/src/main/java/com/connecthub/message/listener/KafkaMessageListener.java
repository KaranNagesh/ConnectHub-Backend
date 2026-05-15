package com.connecthub.message.listener;

import com.connecthub.message.config.SubscriptionTierLimits;
import com.connecthub.message.entity.Message;
import com.connecthub.message.exception.TooManyRequestsException;
import com.connecthub.message.service.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * KafkaMessageListener — Consumes Inbound Chat Messages from Kafka for Persistence
 *
 * PURPOSE:
 *   Listens to the "chat.messages.inbound" Kafka topic and persists each message
 *   to the database. This is the async/fallback persistence path — websocket-service
 *   also attempts a synchronous Feign call to save messages, but this Kafka path
 *   ensures nothing is lost if the direct call fails or message-service was briefly down.
 *
 * MESSAGE PROCESSING PIPELINE:
 *   For each consumed message, the following checks run in order:
 *
 *   1. VALIDATION — reject messages with null senderId or roomId (malformed events).
 *
 *   2. IDEMPOTENCY — check if the messageId already exists in the database. If it does,
 *      the Feign call already persisted it; skip re-insertion but still emit to
 *      "chat.messages.outbound" so downstream consumers get the event. This prevents
 *      double-counting rate limits and guest caps on re-consumed messages.
 *
 *   3. GUEST LIFETIME CAP — guests (usernames starting with "guest_") are limited to
 *      50 messages total (tracked via Redis counter "guest:limits:{userId}"). Once
 *      the cap is hit, a rejection event is emitted to "chat.messages.rejected" so
 *      websocket-service can relay the error message back to the guest's STOMP session.
 *
 *   4. TIER RATE LIMIT — MessageService.send() enforces the per-minute message cap
 *      based on the subscription tier. If exceeded, a rate limit rejection is emitted.
 *
 *   5. PERSISTENCE + OUTBOUND — save the message via MessageService.send() and emit
 *      the saved entity to "chat.messages.outbound" for any downstream consumers.
 *
 * KAFKA RELIABILITY:
 *   - containerFactory is configured with retry (3 attempts) + Dead Letter Queue.
 *   - Failed messages after all retries go to "chat.messages.inbound.dlq".
 *   - The DLQ listener logs these permanently-failed messages for ops investigation.
 *   - @SneakyThrows handles checked exceptions since the Kafka listener interface
 *     doesn't declare them; the Kafka retry interceptor catches all thrown exceptions.
 *
 * REJECTION EVENTS:
 *   When a message is rejected (guest cap or rate limit), a JSON event is published
 *   to "chat.messages.rejected". websocket-service subscribes to this and forwards
 *   the error message to the sender's personal STOMP queue so they see the error
 *   immediately in the UI without waiting for an HTTP response.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaMessageListener {

    private final MessageService messageService;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /*
     * Lifetime message cap for guest users. After 50 messages, the guest sees a
     * prompt to sign up. This counter is stored in Redis and never resets.
     */
    private static final int GUEST_MESSAGE_LIMIT = 50;

    /**
     * processInboundMessage — the main Kafka listener for inbound chat messages.
     *
     * HOW IT WORKS:
     *   Deserializes the JSON payload, validates required fields, runs idempotency
     *   and cap checks in order, then persists the message and emits the outbound event.
     *   Each step is logged with topic/partition/offset for distributed tracing.
     *
     * @SneakyThrows wraps checked exceptions so they propagate as unchecked
     * to the Kafka retry infrastructure without losing the stack trace.
     */
    @SneakyThrows
    @KafkaListener(
            topics = "chat.messages.inbound",
            groupId = "message-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void processInboundMessage(
            @Payload String messageJson,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.debug("Consuming {}[{}]@{}", topic, partition, offset);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(messageJson, Map.class);
        String roomId = (String) payload.get("roomId");
        Integer senderId = toInt(payload.get("senderId"));
        String senderUsername = (String) payload.get("senderUsername");
        String subscriptionTier = (String) payload.get("subscriptionTier");

        if (senderId == null || roomId == null) {
            log.error("Invalid inbound message — missing senderId/roomId at {}[{}]@{}: {}",
                    topic, partition, offset, payload);
            return;
        }

        String messageId = (String) payload.get("messageId");

        /*
         * Idempotency check before any counting: if the message was already saved by the
         * synchronous Feign call from websocket-service, skip the insert. Still emit
         * to outbound so downstream consumers (analytics, search index) get the event.
         */
        if (messageId != null && messageService.existsById(messageId)) {
            log.debug("Message {} already persisted — emitting outbound only ({}[{}]@{})",
                    messageId, topic, partition, offset);
            kafkaTemplate.send("chat.messages.outbound", objectMapper.writeValueAsString(payload));
            return;
        }

        /*
         * Guest lifetime cap: increment the counter and reject if over the limit.
         * The Redis key never expires — once a guest hits 50 messages, they stay blocked
         * until they register. This runs after idempotency to avoid double-counting
         * re-consumed messages against the guest budget.
         */
        boolean isGuest = senderUsername != null && senderUsername.startsWith("guest_");
        if (isGuest) {
            String limitKey = "guest:limits:" + senderId;
            Long count = redisTemplate.opsForValue().increment(limitKey);
            if (count != null && count > GUEST_MESSAGE_LIMIT) {
                log.warn("Guest {} hit message limit in room {} at {}[{}]@{}",
                        senderId, roomId, topic, partition, offset);
                emitRejection(senderId, roomId,
                        "You've reached your free 50 message limit. Please sign up to continue chatting!");
                return;
            }
        }

        Message msg = new Message();
        msg.setMessageId(messageId);
        msg.setRoomId(roomId);
        msg.setSenderId(senderId);
        msg.setContent((String) payload.get("content"));
        msg.setType((String) payload.get("type"));
        msg.setMediaUrl((String) payload.get("mediaUrl"));
        msg.setThumbnailUrl((String) payload.get("thumbnailUrl"));
        msg.setReplyToMessageId((String) payload.get("replyToMessageId"));

        try {
            String tier = SubscriptionTierLimits.normalizeTier(subscriptionTier);
            Message saved = messageService.send(msg, tier);
            kafkaTemplate.send("chat.messages.outbound", objectMapper.writeValueAsString(saved));
            log.info("Processed message {} for room {} ({}[{}]@{})",
                    messageId, roomId, topic, partition, offset);
        } catch (TooManyRequestsException ex) {
            log.warn("User {} hit tier message rate limit at {}[{}]@{}", senderId, topic, partition, offset);
            emitRateLimitRejection(senderId, roomId, ex.getLimit());
        }
    }

    /**
     * emitRejection — publishes a general limit rejection event.
     * Used when a guest user exceeds their 50-message lifetime cap.
     * websocket-service consumes "chat.messages.rejected" and forwards the
     * userMessage string to the sender's personal STOMP error queue.
     */
    private void emitRejection(int senderId, String roomId, String userMessage) throws Exception {
        Map<String, Object> rejection = new HashMap<>();
        rejection.put("senderId", senderId);
        rejection.put("roomId", roomId);
        rejection.put("reason", "LIMIT_EXCEEDED");
        rejection.put("message", userMessage);
        kafkaTemplate.send("chat.messages.rejected", objectMapper.writeValueAsString(rejection));
    }

    /**
     * emitRateLimitRejection — publishes a rate-limit rejection event.
     * Used when a user exceeds their per-minute message cap for their subscription tier.
     * Includes the limit value so the frontend can show "X messages/minute on your plan."
     */
    private void emitRateLimitRejection(int senderId, String roomId, int limit) throws Exception {
        Map<String, Object> rejection = new HashMap<>();
        rejection.put("senderId", senderId);
        rejection.put("roomId", roomId);
        rejection.put("reason", "RATE_LIMIT");
        rejection.put("limit", limit);
        rejection.put("message", "You've reached your plan's messages per minute limit. Upgrade to PRO for a higher limit.");
        kafkaTemplate.send("chat.messages.rejected", objectMapper.writeValueAsString(rejection));
    }

    /**
     * handleDlq — consumes messages that exhausted all retry attempts.
     * Logs the raw message for ops investigation and potential manual replay.
     * In production, this should trigger an alert so no message loss goes unnoticed.
     */
    @KafkaListener(
            topics = "chat.messages.inbound.dlq",
            groupId = "message-service-dlq-group"
    )
    public void handleDlq(
            @Payload String messageJson,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset) {
        log.error("DLQ MESSAGE at {}@{}: {} — message persistence permanently failed",
                topic, offset, messageJson);
    }

    /**
     * toInt — safely converts an Object (from JSON deserialization) to Integer.
     * JSON numbers from objectMapper.readValue() may come as Integer, Long, or String
     * depending on the value size and the JSON library's type inference. This helper
     * handles all three cases and returns null for non-parseable values.
     */
    private static Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Integer i) return i;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString()); }
        catch (NumberFormatException e) { return null; }
    }
}
