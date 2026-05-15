package com.connecthub.auth.listener;

import com.connecthub.auth.repository.UserRepository;
import com.connecthub.auth.service.UserProfileCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * P3.5 — Hardened Kafka listener for subscription status events.
 *
 * - containerFactory wires in retry + DLQ from KafkaConfig
 * - Logs topic/partition/offset for traceability
 * - DLQ listener captures permanently failed messages
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaSubscriptionListener {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final UserProfileCacheService profileCache;

    @SneakyThrows
    @KafkaListener(
            topics = "user.subscription.status",
            groupId = "auth-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void processSubscriptionStatus(
            @Payload String payloadJson,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.debug("Consuming {}[{}]@{}", topic, partition, offset);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.convertValue(
                objectMapper.readValue(payloadJson, Map.class), Map.class);

        Integer userId = (Integer) payload.get("userId");
        String status  = (String)  payload.get("status");

        userRepository.findById(userId).ifPresentOrElse(user -> {
            user.setSubscriptionTier(status);
            userRepository.save(user);
            profileCache.evict(userId);
            log.info("User {} subscription updated to {} ({}[{}]@{})",
                    userId, status, topic, partition, offset);
        }, () -> log.warn("User {} not found for subscription update — skipping", userId));
    }

    @KafkaListener(
            topics = "user.subscription.status.dlq",
            groupId = "auth-service-dlq-group"
    )
    public void handleDlq(
            @Payload String payloadJson,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset) {
        log.error("DLQ MESSAGE at {}@{}: {} — subscription status update permanently failed",
                topic, offset, payloadJson);
    }
}
