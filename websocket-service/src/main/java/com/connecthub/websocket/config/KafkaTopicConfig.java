package com.connecthub.websocket.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * P3.1 — Kafka Topic Registry for websocket-service
 *
 * Declares all topics this service PRODUCES or CONSUMES so they are
 * auto-created with correct partition/replication settings on startup.
 * DLQ topics follow naming convention: {topic}.dlq
 *
 * Partitions: 3 (enables parallel consumption across pods)
 * Replicas: 1 (single-broker dev; raise to 3 in prod)
 */
@Configuration
public class KafkaTopicConfig {

    // ── Topics produced by websocket-service ─────────────────────────────────
    @Bean public NewTopic chatMessagesInbound() {
        return TopicBuilder.name("chat.messages.inbound").partitions(3).replicas(1).build();
    }
    @Bean public NewTopic chatMessagesInboundDlq() {
        return TopicBuilder.name("chat.messages.inbound.dlq").partitions(1).replicas(1).build();
    }
    @Bean public NewTopic notificationsOffline() {
        return TopicBuilder.name("notifications.offline").partitions(3).replicas(1).build();
    }
    @Bean public NewTopic notificationsOfflineDlq() {
        return TopicBuilder.name("notifications.offline.dlq").partitions(1).replicas(1).build();
    }
    @Bean public NewTopic roomUpdatesTimestamp() {
        return TopicBuilder.name("room.updates.timestamp").partitions(3).replicas(1).build();
    }
    @Bean public NewTopic roomUpdatesTimestampDlq() {
        return TopicBuilder.name("room.updates.timestamp.dlq").partitions(1).replicas(1).build();
    }
    @Bean public NewTopic chatMessagesRejected() {
        return TopicBuilder.name("chat.messages.rejected").partitions(3).replicas(1).build();
    }
    @Bean public NewTopic chatMessagesRejectedDlq() {
        return TopicBuilder.name("chat.messages.rejected.dlq").partitions(1).replicas(1).build();
    }
    @Bean public NewTopic roomCreatedConsumed() {
        return TopicBuilder.name("room.created").partitions(3).replicas(1).build();
    }
}
