package com.connecthub.message.service;
import com.connecthub.message.config.SubscriptionTierLimits;
import com.connecthub.message.entity.*;
import com.connecthub.message.exception.TooManyRequestsException;
import com.connecthub.message.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MessageService — Core Business Logic for Chat Messages
 *
 * PURPOSE:
 *   This is the central service for all message operations: sending, reading,
 *   editing, deleting, searching, and reacting to messages. It is the single
 *   point responsible for message validation, XSS sanitization, rate limiting,
 *   and database persistence.
 *
 * TWO ENTRY PATHS FOR SEND:
 *   Messages arrive here via two different paths:
 *   1. WebSocket path: websocket-service receives the message over STOMP, escapes
 *      it for XSS, publishes to Redis/Kafka, then calls MessageResource.saveFromKafka()
 *      or the Feign client — at this point the content is ALREADY HTML-escaped.
 *   2. Direct REST path: a client calls POST /api/v1/messages directly (e.g., via
 *      Swagger or API testing) — the content has NOT been escaped yet.
 *   The isAlreadyEscaped() heuristic detects which path was used and only escapes
 *   content that hasn't been escaped already. This prevents double-encoding.
 *
 * RATE LIMITING:
 *   The send() method enforces a per-minute message quota based on the user's
 *   subscription tier (FREE or PRO) using MessageTierRateLimiter. This is checked
 *   at the service layer — not just the gateway — so it applies to both HTTP and
 *   Kafka-consumed messages. If the limit is exceeded, TooManyRequestsException is thrown
 *   with the limit value so the response body can tell the user their cap.
 *
 * XSS SANITIZATION STRATEGY:
 *   Spring's HtmlUtils.htmlEscape() converts characters like < > & " ' into safe
 *   HTML entities (&lt; &gt; &amp; etc.), preventing cross-site scripting if messages
 *   are ever rendered in a browser as raw HTML. The key rule is: escape EXACTLY ONCE.
 *   If content was already escaped by websocket-service (which it is for all WebSocket
 *   messages), escaping again would corrupt the stored content by double-encoding
 *   entities (e.g., &amp; would become &amp;amp;).
 *
 * SOFT DELETE:
 *   delete() sets the isDeleted flag to true rather than physically removing the row.
 *   This preserves message history for audit purposes and allows the frontend to
 *   display a "This message was deleted" placeholder instead of breaking thread continuity.
 *
 * REACTIONS:
 *   Emoji reactions are stored in a separate MessageReaction table linked by messageId.
 *   addReaction() prevents duplicate reactions from the same user with the same emoji
 *   using a uniqueness check in the repository.
 */
@SuppressWarnings("null")
@Service @RequiredArgsConstructor @Slf4j @Transactional
public class MessageService {
    private final MessageRepository msgRepo;
    private final ReactionRepository reactRepo;
    private final MessageTierRateLimiter messageTierRateLimiter;

    /**
     * send — persists a new chat message after rate-limit and XSS checks.
     *
     * HOW IT WORKS:
     *   1. Normalize the subscription tier (null → "FREE") and call tryAcquire() on the
     *      rate limiter. If the per-minute cap is exceeded, throw TooManyRequestsException.
     *   2. Check if the content is already HTML-escaped (WebSocket path). If not, escape it.
     *   3. Default the delivery status to "SENT" if not provided.
     *   4. Save and return the persisted Message entity.
     *
     * The rate limiter check is skipped if senderId is null (e.g., system messages
     * that don't originate from a user account).
     */
    public Message send(Message msg, String subscriptionTier) {
        if (msg.getSenderId() != null) {
            String tier = SubscriptionTierLimits.normalizeTier(subscriptionTier);
            if (!messageTierRateLimiter.tryAcquire(String.valueOf(msg.getSenderId()), tier)) {
                throw new TooManyRequestsException(
                        "Message rate limit exceeded for your plan",
                        SubscriptionTierLimits.messagesPerMinute(tier));
            }
        }
        /*
         * XSS sanitization: only escape if the content hasn't been escaped already.
         * Messages arriving from websocket-service are pre-escaped; direct REST calls are not.
         * Escaping twice would corrupt entities like &amp; into &amp;amp; in the database,
         * making the stored copy different from what was broadcast live.
         */
        if (msg.getContent() != null && !isAlreadyEscaped(msg.getContent())) {
            msg.setContent(HtmlUtils.htmlEscape(msg.getContent()));
        }
        if (msg.getDeliveryStatus() == null) msg.setDeliveryStatus("SENT");
        return msgRepo.save(msg);
    }

    /**
     * isAlreadyEscaped — heuristic to detect pre-escaped content from websocket-service.
     *
     * HOW IT WORKS:
     *   Content that has been through HtmlUtils.htmlEscape() will NEVER contain raw < or >
     *   characters — they would have been turned into &lt; and &gt;. So if we see a raw
     *   < or >, the string is definitely NOT escaped.
     *
     *   For &, the check is more nuanced: a raw & that's NOT part of an HTML entity (e.g.,
     *   &amp; or &lt;) means the string is unescaped. We look for & followed by text and a
     *   semicolon within 8 characters. If & appears without a matching entity pattern,
     *   the content is unescaped and we return false.
     *
     *   This is a heuristic — it can theoretically be fooled by crafted content — but in
     *   practice it reliably distinguishes the two entry paths (WebSocket vs REST).
     */
    private boolean isAlreadyEscaped(String s) {
        if (s.indexOf('<') >= 0 || s.indexOf('>') >= 0) return false;
        int i = 0;
        while ((i = s.indexOf('&', i)) >= 0) {
            int semi = s.indexOf(';', i);
            if (semi < 0 || semi - i > 8) return false;
            i = semi + 1;
        }
        return true;
    }

    /**
     * existsById — checks whether a message with the given ID exists in the database.
     * Used by other services (e.g., when validating replyToMessageId references).
     * Returns false for null or blank IDs defensively.
     */
    @Transactional(readOnly = true)
    public boolean existsById(String messageId) {
        if (messageId == null || messageId.isBlank()) return false;
        return msgRepo.existsById(messageId);
    }

    /**
     * getMessages — loads a page of messages for a room in reverse-chronological order.
     *
     * HOW IT WORKS:
     *   If a "before" cursor timestamp is provided, only messages sent before that time
     *   are returned. This enables infinite-scroll pagination: the frontend passes the
     *   sentAt of the oldest visible message as the cursor to load the previous page.
     *   If no cursor is given, the most recent messages are returned.
     *   Deleted messages (isDeleted=true) are always filtered out.
     */
    @Transactional(readOnly = true)
    public List<Message> getMessages(String roomId, LocalDateTime before, int limit) {
        var pageable = PageRequest.of(0, limit);
        if (before != null) return msgRepo.findByRoomIdAndIsDeletedFalseAndSentAtBeforeOrderBySentAtDesc(roomId, before, pageable);
        return msgRepo.findByRoomIdAndIsDeletedFalseOrderBySentAtDesc(roomId, pageable);
    }

    /**
     * edit — updates the content of an existing message.
     *
     * HOW IT WORKS:
     *   Fetches the message by ID and verifies the editorId matches the original sender.
     *   This authorization check prevents users from editing each other's messages.
     *   The new content is HTML-escaped (edit always comes from direct REST, not WebSocket).
     *   isEdited is set to true and editedAt is stamped so the frontend can show the
     *   "(edited)" label next to the message bubble.
     */
    public Message edit(String msgId, String content, int editorId) {
        Message m = msgRepo.findById(msgId).orElseThrow(() -> new RuntimeException("Message not found"));
        if (!m.getSenderId().equals(editorId)) throw new RuntimeException("Not authorized");
        m.setContent(HtmlUtils.htmlEscape(content));
        m.setEdited(true); m.setEditedAt(LocalDateTime.now());
        return msgRepo.save(m);
    }

    /**
     * delete — soft-deletes a message by setting its isDeleted flag.
     *
     * WHY SOFT DELETE:
     *   Physically removing the row would break thread continuity — replies that
     *   reference this message by replyToMessageId would lose their parent. Soft delete
     *   lets the frontend show "This message was deleted" in place of the original content
     *   while keeping the row available for FK references and audit trails.
     *   Only the original sender is allowed to delete their own message.
     */
    public void delete(String msgId, int deleterId) {
        Message m = msgRepo.findById(msgId).orElseThrow(() -> new RuntimeException("Message not found"));
        if (!m.getSenderId().equals(deleterId)) throw new RuntimeException("Not authorized to delete this message");
        m.setDeleted(true); msgRepo.save(m);
    }

    /**
     * search — full-text keyword search within a room's message history.
     * Delegates to a custom @Query in MessageRepository that does a LIKE match
     * against the content column. Only non-deleted messages are returned.
     */
    @Transactional(readOnly = true)
    public List<Message> search(String roomId, String kw) { return msgRepo.searchInRoom(roomId, kw); }

    /**
     * updateStatus — updates the delivery/read status of a message.
     * Status values: "SENT" (stored), "DELIVERED" (reached client), "READ" (user viewed it).
     * Uses ifPresent() to silently ignore unknown message IDs — status updates are
     * best-effort and should not throw errors if the message was deleted.
     */
    public void updateStatus(String msgId, String status) {
        msgRepo.findById(msgId).ifPresent(m -> { m.setDeliveryStatus(status); msgRepo.save(m); });
    }

    /**
     * unreadCount — returns how many unread messages exist in a room for a user.
     *
     * HOW IT WORKS:
     *   If the user has a lastRead timestamp (they've opened the room before), count
     *   only messages sent after that time. If lastRead is null (first visit), count
     *   all non-deleted messages in the room. This gives an accurate unread badge count
     *   based on the database rather than the Redis counter, useful for reconnect sync.
     */
    @Transactional(readOnly = true) public long unreadCount(String roomId, LocalDateTime lastRead) {
        return lastRead == null ? msgRepo.countByRoomIdAndIsDeletedFalse(roomId) : msgRepo.countByRoomIdAndSentAtAfterAndIsDeletedFalse(roomId, lastRead);
    }

    /**
     * clearHistory — permanently deletes all messages in a room from the database.
     * Used when a room is deleted or when an admin clears chat history. Unlike soft
     * delete, this is a hard physical delete — the history cannot be recovered.
     */
    public void clearHistory(String roomId) { msgRepo.deleteByRoomId(roomId); }

    /**
     * addReaction — records an emoji reaction from a user on a specific message.
     * The combination of (messageId, userId, emoji) must be unique — reacting with
     * the same emoji twice is prevented by a repository existence check. Each unique
     * reaction is stored as a separate MessageReaction row.
     */
    public MessageReaction addReaction(String msgId, int uid, String emoji) {
        if (reactRepo.existsByMessageIdAndUserIdAndEmoji(msgId, uid, emoji)) throw new RuntimeException("Already reacted");
        return reactRepo.save(MessageReaction.builder().messageId(msgId).userId(uid).emoji(emoji).build());
    }

    /**
     * removeReaction — deletes a specific emoji reaction by the given user.
     * This is the "un-react" action when a user clicks an active reaction to toggle it off.
     */
    public void removeReaction(String msgId, int uid, String emoji) { reactRepo.deleteByMessageIdAndUserIdAndEmoji(msgId, uid, emoji); }

    /**
     * getReactions — returns all emoji reactions on a message.
     * The frontend groups these by emoji and counts them to show the reaction pill UI
     * (e.g., "👍 3" means 3 users reacted with thumbs up).
     */
    @Transactional(readOnly = true) public List<MessageReaction> getReactions(String msgId) { return reactRepo.findByMessageId(msgId); }

    @Transactional(readOnly = true)
    public Map<String, Object> adminAnalytics() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime sevenDaysAgo = now.minusDays(6).toLocalDate().atStartOfDay();
        Map<String, Long> messagesPerDay = new LinkedHashMap<>();
        for (Object[] row : msgRepo.countMessagesPerDaySince(sevenDaysAgo)) {
            messagesPerDay.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return Map.of(
                "totalMessages", msgRepo.countByIsDeletedFalse(),
                "messagesLast24h", msgRepo.countBySentAtAfterAndIsDeletedFalse(now.minusHours(24)),
                "messagesPerDay", messagesPerDay
        );
    }
}
