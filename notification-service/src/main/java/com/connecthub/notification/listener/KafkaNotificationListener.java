package com.connecthub.notification.listener;

import com.connecthub.notification.client.AuthServiceClient;
import com.connecthub.notification.dto.UserProfileDto;
import com.connecthub.notification.entity.Notification;
import com.connecthub.notification.service.EmailSender;
import com.connecthub.notification.service.FcmSender;
import com.connecthub.notification.service.NotifService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * KafkaNotificationListener — Consumes Offline Notification Events from Kafka
 *
 * PURPOSE:
 *   Listens to the "notifications.offline" Kafka topic and creates persistent
 *   in-app notifications for users who were offline when a message arrived.
 *   This is the bridge between the real-time messaging pipeline and the persistent
 *   notification store that powers the bell-icon badge.
 *
 * HOW IT FITS IN THE FLOW:
 *   When websocket-service detects that a message recipient is offline, it publishes
 *   an event to the "notifications.offline" Kafka topic (via DeliveryService.createNotification()).
 *   This listener consumes those events and calls NotifService.send() to:
 *   1. Persist the notification in MySQL (survives reconnect).
 *   2. Push it to the user in real-time via Redis pub/sub (in case they come online
 *      before they explicitly refresh — the push reaches them immediately).
 *
 * KAFKA RELIABILITY CONFIGURATION:
 *   The kafkaListenerContainerFactory (configured in KafkaConfig) uses:
 *   - Retry: failed processing is retried up to 3 times with backoff before giving up.
 *   - Dead Letter Queue (DLQ): messages that fail all retries are sent to
 *     "notifications.offline.dlq" rather than being silently discarded.
 *   - Offset commit happens after successful DB write — if the process crashes mid-write,
 *     the message is re-consumed and processed again on restart (at-least-once delivery).
 *
 * MESSAGE FORMAT:
 *   The JSON payload on "notifications.offline" contains:
 *   - recipientId: the user who should receive the notification
 *   - actorId:     the user who triggered the event (message sender)
 *   - type:        "NEW_MESSAGE" (used by frontend to route and display the notification)
 *   - title:       notification title ("New message from Alice")
 *   - message:     content preview (first 100 chars of the message)
 *   - roomId:      the room where the message was sent
 *   - messageId:   the specific message that triggered the notification
 *
 * DLQ HANDLER:
 *   handleDlq() logs dead-lettered messages for ops visibility. In production this
 *   should integrate with an alerting system (PagerDuty, Slack) so failed notifications
 *   can be investigated and replayed if necessary.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaNotificationListener {

    private final NotifService notifService;
    private final ObjectMapper objectMapper;
    private final AuthServiceClient authServiceClient;
    private final EmailSender emailSender;
    private final FcmSender fcmSender;

    /**
     * processOfflineNotification — consumes an offline notification event and persists it.
     *
     * HOW IT WORKS:
     *   1. Deserialize the JSON payload from the Kafka message into a Map.
     *   2. Build a Notification entity from the map fields.
     *   3. Call NotifService.send() which saves to DB and pushes to Redis for real-time delivery.
     *   4. Log the topic/partition/offset for traceability.
     *
     * @SneakyThrows wraps the checked JsonProcessingException from objectMapper.readValue()
     * since throwing it through the Kafka listener signature would require re-wrapping.
     * The Kafka retry configuration catches any thrown exception and applies retry logic.
     */
    @SneakyThrows
    @KafkaListener(
            topics = "notifications.offline",
            groupId = "notification-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void processOfflineNotification(
            @Payload String messageJson,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.debug("Consuming {}[{}]@{}", topic, partition, offset);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.convertValue(
                objectMapper.readValue(messageJson, Map.class), Map.class);

        Notification notif = new Notification();
        notif.setRecipientId((Integer) payload.get("recipientId"));
        notif.setActorId((Integer) payload.get("actorId"));
        notif.setType((String) payload.get("type"));
        notif.setTitle((String) payload.get("title"));
        notif.setMessage((String) payload.get("message"));
        notif.setRoomId((String) payload.get("roomId"));
        notif.setMessageId((String) payload.get("messageId"));
        notif.setRead(false);
        notif.setCreatedAt(LocalDateTime.now());

        notifService.send(notif);
        dispatchPushNotification(notif);
        maybeSendMissedDirectMessageEmail(payload, notif);
        log.info("Processed offline notification for user {} from {}[{}]@{}",
                notif.getRecipientId(), topic, partition, offset);
    }

    private void dispatchPushNotification(Notification notif) {
        Map<String, String> data = new HashMap<>();
        if (notif.getRoomId() != null) data.put("roomId", notif.getRoomId());
        if (notif.getMessageId() != null) data.put("messageId", notif.getMessageId());
        if (notif.getType() != null) data.put("type", notif.getType());
        fcmSender.sendToUser(notif.getRecipientId(), notif.getTitle(), notif.getMessage(), data);
    }

    private void maybeSendMissedDirectMessageEmail(Map<String, Object> payload, Notification notif) {
        if (!"NEW_MESSAGE".equals(notif.getType())) return;
        if (!"DM".equals(String.valueOf(payload.getOrDefault("roomType", "")))) return;
        try {
            UserProfileDto recipient = authServiceClient.getProfile(notif.getRecipientId());
            if (recipient == null || recipient.getEmail() == null || recipient.getEmail().isBlank()) return;

            LocalDateTime lastSeenAt = recipient.getLastSeenAt();
            if (lastSeenAt == null || lastSeenAt.isAfter(LocalDateTime.now().minusMinutes(30))) return;

            UserProfileDto actor = notif.getActorId() == null ? null : authServiceClient.getProfile(notif.getActorId());
            String senderName = actor != null
                    ? (actor.getFullName() != null && !actor.getFullName().isBlank() ? actor.getFullName() : actor.getUsername())
                    : "Someone";
            emailSender.sendMissedDirectMessage(recipient.getEmail(), senderName, notif.getMessage(), notif.getRoomId());
        } catch (Exception e) {
            log.warn("Missed-message email skipped for user {}: {}", notif.getRecipientId(), e.getMessage());
        }
    }

    /**
     * handleDlq — consumes messages that failed all retry attempts.
     *
     * HOW IT WORKS:
     *   After 3 failed retries (configured in KafkaConfig), the Kafka retry interceptor
     *   routes the message to "notifications.offline.dlq". This listener logs the raw
     *   message content so ops teams can investigate the failure cause and replay the
     *   message manually if needed.
     *
     *   In production, this should be wired to an alerting system (PagerDuty, Slack
     *   webhook, etc.) so dead-lettered notifications trigger immediate investigation
     *   rather than silently disappearing.
     */
    @KafkaListener(
            topics = "notifications.offline.dlq",
            groupId = "notification-service-dlq-group"
    )
    public void handleDlq(
            @Payload String messageJson,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset) {
        log.error("DLQ MESSAGE at {}@{}: {} — manual intervention required", topic, offset, messageJson);
    }
}
