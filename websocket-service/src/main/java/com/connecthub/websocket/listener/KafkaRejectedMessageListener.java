package com.connecthub.websocket.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * KafkaRejectedMessageListener — Forwards Message Rejection Notices to Clients via STOMP
 *
 * PURPOSE:
 *   Listens to the "chat.messages.rejected" Kafka topic and forwards rejection
 *   payloads to the affected user's personal STOMP error queue. This closes the
 *   feedback loop when a message is rejected by message-service after the user
 *   already sent it — without this, the client would send a message and never
 *   receive any feedback that it was blocked.
 *
 * REJECTION SCENARIOS:
 *   Two types of rejections arrive on this topic:
 *   1. LIMIT_EXCEEDED — guest user hit the 50-message lifetime cap.
 *      The "message" field contains a human-readable prompt to sign up.
 *   2. RATE_LIMIT — user exceeded their per-minute message quota for their tier.
 *      The "limit" field tells the client the cap value so it can display it.
 *
 * DELIVERY TO FRONTEND:
 *   convertAndSendToUser(senderId, "/queue/errors", payload) delivers to
 *   /user/{senderId}/queue/errors on the STOMP broker. The frontend subscribes
 *   to this destination and shows an inline error banner in the chat input area
 *   (e.g., "You've reached your 50 message limit. Sign up to continue.").
 *   This is a no-op if the user is no longer connected to this pod — they'll
 *   see the error if they reconnect before the Kafka message is consumed.
 *
 * KAFKA RELIABILITY:
 *   Uses the same kafkaListenerContainerFactory with retry + DLQ as other listeners.
 *   Permanently-failed delivery attempts (all retries exhausted) go to
 *   "chat.messages.rejected.dlq" where handleDlq() logs them for ops visibility.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaRejectedMessageListener {

    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;

    /**
     * processRejectedMessage — consumes a rejection event and pushes it to the sender.
     *
     * HOW IT WORKS:
     *   1. Deserialize the JSON payload to a Map.
     *   2. Extract the senderId to identify which user's STOMP session to target.
     *   3. Call convertAndSendToUser() to deliver the full rejection map to
     *      /user/{senderId}/queue/errors — the frontend reads the "message" field
     *      to display the error text.
     *   Missing senderId is logged and dropped — there's no way to deliver without it.
     */
    @SneakyThrows
    @KafkaListener(
            topics = "chat.messages.rejected",
            groupId = "websocket-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void processRejectedMessage(
            @Payload String payloadJson,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.debug("Consuming {}@{}", topic, offset);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.convertValue(
                objectMapper.readValue(payloadJson, Map.class), Map.class);

        Object senderIdObj = payload.get("senderId");
        if (senderIdObj == null) {
            log.warn("Rejected message payload missing senderId at {}@{} — dropping", topic, offset);
            return;
        }

        String senderId = senderIdObj.toString();
        messaging.convertAndSendToUser(senderId, "/queue/errors", payload);
        log.info("Forwarded rejection notice to user {} ({}@{})", senderId, topic, offset);
    }

    /**
     * handleDlq — logs rejection notices that failed all retry attempts.
     * These are rare (network or broker failure during a STOMP push) but logged
     * so ops can identify if users are systematically not receiving error feedback.
     */
    @KafkaListener(
            topics = "chat.messages.rejected.dlq",
            groupId = "websocket-service-dlq-group"
    )
    public void handleDlq(
            @Payload String payloadJson,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset) {
        log.error("DLQ MESSAGE at {}@{}: {} — rejected message forwarding permanently failed",
                topic, offset, payloadJson);
    }
}
