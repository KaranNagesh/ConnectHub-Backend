package com.connecthub.websocket.handler;

import com.connecthub.websocket.client.MessageServiceClient;
import com.connecthub.websocket.client.RoomServiceClient;
import com.connecthub.websocket.config.RedisConfig;
import com.connecthub.websocket.dto.*;
import com.connecthub.websocket.interceptor.StompPrincipal;
import com.connecthub.websocket.service.DeliveryService;
import com.connecthub.websocket.service.MessagePersistenceService;
import com.connecthub.websocket.service.TypingService;
import com.connecthub.websocket.service.UnreadCountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.util.HtmlUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatWebSocketHandler {

    private final SimpMessagingTemplate messaging;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MessagePersistenceService persistenceService;
    private final MessageServiceClient messageServiceClient;
    private final TypingService typingService;
    private final DeliveryService deliveryService;
    private final UnreadCountService unreadCountService;
    private final RoomServiceClient roomServiceClient;

    @MessageMapping("/chat.send")
    public void handleChat(@Payload ChatMessagePayload p, SimpMessageHeaderAccessor h) {
        try {
            if (!(h.getUser() instanceof StompPrincipal stomp)) {
                log.warn("Unauthenticated or invalid principal — dropping message");
                return;
            }

            String uid = stomp.getName();
            String username = stomp.username();
            String subscriptionTier = stomp.subscriptionTier();

            if (p.getMessageId() == null || p.getMessageId().isBlank()) {
                p.setMessageId(UUID.randomUUID().toString());
            }

            if (p.getRoomId() == null || p.getRoomId().isBlank()) {
                log.warn("No roomId — dropping");
                sendError(uid, "Room is required before sending a message.");
                return;
            }

            if (!isRoomMember(p.getRoomId(), uid)) {
                log.warn("User {} not member of room {}", uid, p.getRoomId());
                sendError(uid, "You are not a member of this room.");
                return;
            }

            // enrich payload
            p.setSenderId(Integer.parseInt(uid));
            p.setSenderUsername(username);
            p.setSubscriptionTier(subscriptionTier != null ? subscriptionTier : "FREE");
            p.setTimestamp(System.currentTimeMillis());
            p.setType(p.getType() != null ? p.getType() : "TEXT");
            p.setDeliveryStatus("SENT");

            if (p.getContent() != null) {
                p.setContent(HtmlUtils.htmlEscape(p.getContent()));
            }

            // Persist first. Only broadcast after the message-service accepts the
            // message, or after Kafka has accepted it as the fallback path.
            boolean savedSynchronously = false;
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("messageId", p.getMessageId());
                body.put("roomId", p.getRoomId());
                body.put("senderId", p.getSenderId());
                body.put("content", p.getContent());
                body.put("type", p.getType());
                body.put("deliveryStatus", p.getDeliveryStatus());
                if (p.getMediaUrl() != null) body.put("mediaUrl", p.getMediaUrl());
                if (p.getThumbnailUrl() != null) body.put("thumbnailUrl", p.getThumbnailUrl());
                if (p.getReplyToMessageId() != null) body.put("replyToMessageId", p.getReplyToMessageId());

                Map<String, Object> saved = messageServiceClient.persistMessage(body, uid, p.getSubscriptionTier());

                if (saved != null) {
                    if (saved.get("sentAt") != null)
                        p.setSentAt(saved.get("sentAt").toString());

                    if (saved.get("messageId") != null)
                        p.setMessageId(saved.get("messageId").toString());
                }

                savedSynchronously = true;
            } catch (FeignException e) {
                if (e.status() >= 400 && e.status() < 500) {
                    log.warn("Message rejected by message-service with status {}", e.status());
                    sendError(uid, userMessageForStatus(e.status()));
                    return;
                }
                log.warn("Persist failed, fallback to Kafka: {}", e.getMessage());
            } catch (Exception e) {
                log.warn("Persist failed, fallback to Kafka: {}", e.getMessage());
            }

            boolean queuedForPersistence = savedSynchronously || persistenceService.persistMessage(p);
            if (!queuedForPersistence) {
                sendError(uid, "Message service is temporarily unavailable. Please try again.");
                return;
            }

            // Redis broadcast
            redis.convertAndSend(RedisConfig.CHAT_CHANNEL, mapper.writeValueAsString(p));

            // update room timestamp
            deliveryService.updateRoomTimestamp(p.getRoomId(), uid);

        } catch (Exception e) {
            log.error("Chat error", e);
        }
    }

    @MessageMapping("/chat.typing")
    public void handleTyping(@Payload TypingIndicatorPayload p, SimpMessageHeaderAccessor h) {
        if (!(h.getUser() instanceof StompPrincipal stomp)) return;

        String uid = stomp.getName();

        if (!isRoomMember(p.getRoomId(), uid)) return;

        p.setSenderId(Integer.parseInt(uid));
        p.setSenderUsername(stomp.username());

        typingService.setTyping(p.getRoomId(), Integer.parseInt(uid));

        messaging.convertAndSend("/topic/room/" + p.getRoomId() + "/typing", p);
    }

    @MessageMapping("/chat.read")
    public void handleRead(@Payload ReadReceiptPayload p, SimpMessageHeaderAccessor h) {
        if (!(h.getUser() instanceof StompPrincipal stomp)) return;

        String uid = stomp.getName();

        if (!isRoomMember(p.getRoomId(), uid)) return;

        p.setReaderId(Integer.parseInt(uid));

        messaging.convertAndSend("/topic/room/" + p.getRoomId() + "/read", p);

        deliveryService.persistLastRead(p.getRoomId(), uid);
        unreadCountService.reset(Integer.parseInt(uid), p.getRoomId());
    }

    @MessageMapping("/chat.react")
    public void handleReaction(@Payload ReactionPayload p, SimpMessageHeaderAccessor h) {
        try {
            if (!(h.getUser() instanceof StompPrincipal stomp)) return;

            if (!isRoomMember(p.getRoomId(), stomp.getName())) return;

            p.setSenderId(Integer.parseInt(stomp.getName()));

            redis.convertAndSend(RedisConfig.REACTION_CHANNEL, mapper.writeValueAsString(p));

        } catch (Exception e) {
            log.error("Reaction error", e);
        }
    }

    @MessageMapping("/chat.edit")
    public void handleEdit(@Payload MessageEditPayload p, SimpMessageHeaderAccessor h) {
        try {
            if (!(h.getUser() instanceof StompPrincipal stomp)) return;

            if (!isRoomMember(p.getRoomId(), stomp.getName())) return;

            p.setEditorId(Integer.parseInt(stomp.getName()));

            if (p.getNewContent() != null) {
                p.setNewContent(HtmlUtils.htmlEscape(p.getNewContent()));
            }

            redis.convertAndSend(RedisConfig.EDIT_CHANNEL, mapper.writeValueAsString(p));

        } catch (Exception e) {
            log.error("Edit error", e);
        }
    }

    @MessageMapping("/chat.delete")
    public void handleDelete(@Payload MessageDeletePayload p, SimpMessageHeaderAccessor h) {
        try {
            if (!(h.getUser() instanceof StompPrincipal stomp)) return;

            if (!isRoomMember(p.getRoomId(), stomp.getName())) return;

            p.setDeleterId(Integer.parseInt(stomp.getName()));

            redis.convertAndSend(RedisConfig.DELETE_CHANNEL, mapper.writeValueAsString(p));

        } catch (Exception e) {
            log.error("Delete error", e);
        }
    }

    private boolean isRoomMember(String roomId, String userId) {
        try {
            Boolean member = roomServiceClient.isMember(roomId, userId, userId);
            return Boolean.TRUE.equals(member);
        } catch (Exception e) {
            log.warn("Membership check failed");
            return false;
        }
    }

    private void sendError(String userId, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("message", message);
        messaging.convertAndSendToUser(userId, "/queue/errors", payload);
    }

    private String userMessageForStatus(int status) {
        return switch (status) {
            case 429 -> "You've reached your message limit. Please slow down and try again.";
            case 403 -> "You are not allowed to send messages in this room.";
            case 400 -> "This message could not be sent. Please check it and try again.";
            default -> "This message could not be sent. Please try again.";
        };
    }
}
