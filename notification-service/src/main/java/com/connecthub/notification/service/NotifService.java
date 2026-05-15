package com.connecthub.notification.service;

import com.connecthub.notification.entity.Notification;
import com.connecthub.notification.repository.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * NotifService — Persistent In-App Notification Management
 *
 * PURPOSE:
 *   Creates, stores, and manages in-app notifications for users. These are the
 *   bell-icon notifications shown in the header (e.g., "Alice sent you a message",
 *   "Bob reacted to your message"). Unlike ephemeral WebSocket pushes, these
 *   notifications are persisted in the database so they survive page refreshes and
 *   are visible when the user logs back in after being offline.
 *
 * TWO-LAYER DELIVERY:
 *   When send() is called, it does two things:
 *   1. PERSIST: saves the Notification entity to MySQL for durable storage.
 *   2. REAL-TIME PUSH: publishes the saved notification as JSON to the Redis
 *      "chat:notifications" pub/sub channel. All websocket-service pods subscribe
 *      to this channel via RedisMessageSubscriber. The pod that has the recipient's
 *      WebSocket connection delivers it to /user/{id}/queue/notifications immediately
 *      so the bell badge updates without requiring a page refresh.
 *
 * HOW NOTIFICATIONS ARE CREATED:
 *   - Offline message notifications: DeliveryService in websocket-service calls
 *     Kafka with "notifications.offline" events. KafkaNotificationListener consumes
 *     this and calls NotifService.send() to persist and push the notification.
 *   - Other events (reactions, mentions, etc.) follow the same path via Kafka.
 *
 * READ STATE:
 *   Each notification has an isRead flag. markRead() sets it for a single notification;
 *   markAllRead() bulk-updates all unread notifications for a user in one query.
 *   unreadCount() returns the count shown on the bell badge in the frontend header.
 *
 * ORDERING:
 *   getByRecipient() returns notifications ordered by createdAt descending so the
 *   most recent notifications appear at the top of the dropdown list.
 */
@Service @RequiredArgsConstructor @Slf4j @Transactional
public class NotifService {
    private final NotificationRepository repo;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /*
     * Redis pub/sub channel for real-time notification delivery.
     * All websocket-service pods subscribe to this channel via RedisListenerConfig.
     * The pod that has the recipient's session delivers it to their personal queue.
     */
    private static final String NOTIF_CHANNEL = "chat:notifications";

    /**
     * send — persists a notification and pushes it to the user in real-time.
     *
     * HOW IT WORKS:
     *   1. Save the Notification entity to the database (durable storage).
     *   2. Serialize the saved entity (now with its generated ID and createdAt) to JSON.
     *   3. Publish to the Redis "chat:notifications" channel. websocket-service pods
     *      listening on this channel will forward it to the user's STOMP personal queue.
     *   Redis publish failures are caught and logged without blocking — the notification
     *   is already persisted so the user will see it on next page load even if the
     *   real-time push fails.
     */
    public Notification send(Notification n) {
        if (hasMessageIdentity(n)) {
            var existing = repo.findFirstByRecipientIdAndTypeAndMessageIdOrderByCreatedAtDesc(
                    n.getRecipientId(), n.getType(), n.getMessageId());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        Notification saved = repo.save(n);
        publishNotificationEvent(saved);
        return saved;
    }

    private boolean hasMessageIdentity(Notification n) {
        return n.getRecipientId() != null
                && n.getType() != null
                && n.getMessageId() != null
                && !n.getMessageId().isBlank();
    }

    /**
     * markRead — marks a single notification as read by its database ID.
     * Uses ifPresent() to silently ignore invalid IDs — this can happen if the
     * notification was deleted between the frontend loading the list and clicking it.
     */
    public void markRead(int id) {
        repo.findById(id).ifPresent(n -> { n.setRead(true); repo.save(n); });
    }

    /**
     * markAllRead — bulk-marks all unread notifications for a user as read.
     * Called when the user opens the notification dropdown (clearing the bell badge).
     * Uses a custom @Modifying JPQL query for a single UPDATE rather than N individual saves.
     */
    public void markAllRead(int recipientId) { repo.markAllRead(recipientId); }

    /**
     * getByRecipient — returns all notifications for a user, newest first.
     * Used by the frontend notification dropdown to render the list of recent events.
     */
    @Transactional(readOnly = true)
    public List<Notification> getByRecipient(int rid) {
        return repo.findByRecipientIdOrderByCreatedAtDesc(rid);
    }

    /**
     * unreadCount — returns the count of unread notifications for the badge number.
     * The frontend polls this (or receives a push update) to show the red count bubble
     * on the bell icon in the header.
     */
    @Transactional(readOnly = true)
    public int unreadCount(int rid) { return repo.countByRecipientIdAndIsRead(rid, false); }

    /**
     * delete — permanently removes a notification by ID.
     * Called when the user dismisses or clears a notification from the dropdown.
     */
    public void delete(int id) { repo.deleteById(id); }

    /**
     * publishNotificationEvent — serializes and publishes the notification to Redis pub/sub.
     * The published JSON includes all fields (including recipientId) so each websocket-service
     * pod can extract the recipient and deliver only to that user's personal STOMP queue.
     * Failures are non-fatal — the notification is already saved to the database.
     */
    private void publishNotificationEvent(Notification n) {
        try {
            String json = objectMapper.writeValueAsString(n);
            redis.convertAndSend(NOTIF_CHANNEL, json);
            log.debug("Notification event published for user {}", n.getRecipientId());
        } catch (Exception e) {
            log.warn("Failed to publish notification event: {}", e.getMessage());
        }
    }
}
