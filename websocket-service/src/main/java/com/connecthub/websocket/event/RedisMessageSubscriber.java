package com.connecthub.websocket.event;

import com.connecthub.websocket.config.RedisConfig;
import com.connecthub.websocket.dto.*;
import com.connecthub.websocket.service.DeliveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RedisMessageSubscriber — Redis Pub/Sub Consumer for Cross-Pod Event Fan-Out
 *
 * PURPOSE:
 *   In a horizontally scaled deployment, multiple websocket-service pods run
 *   simultaneously. Each pod only has WebSocket connections for a subset of users.
 *   When pod A receives a message from User 1 and needs to deliver it to User 2
 *   who is connected to pod B, pod A cannot directly push to pod B's users.
 *
 *   The solution: pod A publishes the event to a Redis channel. ALL pods subscribe
 *   to that channel and each pod pushes the event to its own connected clients via
 *   STOMP (SimpMessagingTemplate). This way, every connected client receives the
 *   event regardless of which pod they are connected to.
 *
 * CHANNELS SUBSCRIBED (defined in RedisConfig):
 *   CHAT_CHANNEL     — new chat messages → push to /topic/room/{roomId}
 *   PRESENCE_CHANNEL — online/offline status changes → push to /topic/presence
 *   EDIT_CHANNEL     — message edits → push to /topic/room/{roomId}/edit
 *   DELETE_CHANNEL   — message deletions → push to /topic/room/{roomId}/delete
 *   REACTION_CHANNEL — emoji reactions → push to /topic/room/{roomId}/reactions
 *   NOTIF_CHANNEL    — real-time notification pushes → push to /user/{id}/queue/notifications
 *
 * HOW EACH MESSAGE IS PROCESSED:
 *   1. The raw channel name is read from message.getChannel().
 *   2. A switch on the channel name deserializes the JSON body into the appropriate DTO.
 *   3. SimpMessagingTemplate.convertAndSend() pushes to the STOMP topic or user queue.
 *      All clients subscribed to that topic on THIS pod receive the message.
 *      Clients on other pods receive it through their own subscriber instance.
 *
 * NOTIFICATION CHANNEL HANDLING:
 *   Notifications include a recipientId field identifying which user should receive them.
 *   deliveryService.pushNotification() calls convertAndSendToUser() which delivers
 *   only to the specific user's personal queue (/user/{id}/queue/notifications).
 *   This is a no-op if the user is not connected to this pod.
 *
 * ERROR HANDLING:
 *   Any deserialization or push failure is caught and logged without crashing the
 *   subscriber. A single bad message on one channel should not disrupt delivery
 *   for other messages or other channels.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisMessageSubscriber implements MessageListener {

    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;
    private final DeliveryService deliveryService;

    @Override
    @SuppressWarnings("unchecked")
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        try {
            switch (channel) {
                case RedisConfig.CHAT_CHANNEL -> {
                    /*
                     * New chat message — broadcast to the room topic.
                     * All clients subscribed to /topic/room/{roomId} on this pod receive it.
                     * deliverToRoomMembers also handles offline queuing and unread counting.
                     */
                    ChatMessagePayload p = objectMapper.readValue(message.getBody(), ChatMessagePayload.class);
                    messaging.convertAndSend("/topic/room/" + p.getRoomId(), p);
                    deliveryService.deliverToRoomMembers(p);
                }
                case RedisConfig.PRESENCE_CHANNEL -> {
                    /*
                     * Online/offline presence update — broadcast to all clients listening
                     * on /topic/presence. The frontend updates the online dot for each user.
                     */
                    PresenceUpdatePayload p = objectMapper.readValue(message.getBody(), PresenceUpdatePayload.class);
                    messaging.convertAndSend("/topic/presence", p);
                }
                case RedisConfig.EDIT_CHANNEL -> {
                    /*
                     * Message edit — push to the room's edit sub-topic.
                     * The frontend's ChatArea WebSocket subscription for 'edit' events
                     * updates the message bubble content in place.
                     */
                    MessageEditPayload p = objectMapper.readValue(message.getBody(), MessageEditPayload.class);
                    messaging.convertAndSend("/topic/room/" + p.getRoomId() + "/edit", p);
                }
                case RedisConfig.DELETE_CHANNEL -> {
                    /*
                     * Message deletion — push to the room's delete sub-topic.
                     * The frontend removes the message bubble from the conversation view.
                     */
                    MessageDeletePayload p = objectMapper.readValue(message.getBody(), MessageDeletePayload.class);
                    messaging.convertAndSend("/topic/room/" + p.getRoomId() + "/delete", p);
                }
                case RedisConfig.REACTION_CHANNEL -> {
                    /*
                     * Emoji reaction — push to the room's reactions sub-topic.
                     * The frontend's EmojiReactions component updates the reaction counts.
                     */
                    ReactionPayload p = objectMapper.readValue(message.getBody(), ReactionPayload.class);
                    messaging.convertAndSend("/topic/room/" + p.getRoomId() + "/reactions", p);
                }
                case RedisConfig.NOTIF_CHANNEL -> {
                    /*
                     * Real-time notification push targeted at a specific user.
                     * The recipientId field identifies who should receive it.
                     * convertAndSendToUser delivers to /user/{id}/queue/notifications
                     * which only that user's STOMP session can receive.
                     */
                    Map<String, Object> notif = objectMapper.readValue(message.getBody(), Map.class);
                    Object recipientObj = notif.get("recipientId");
                    if (recipientObj != null) {
                        Integer recipientId = recipientObj instanceof Integer
                                ? (Integer) recipientObj
                                : Integer.parseInt(recipientObj.toString());
                        deliveryService.pushNotification(recipientId, notif);
                    }
                }
                case RedisConfig.BROADCAST_CHANNEL -> {
                    Map<String, Object> payload = objectMapper.readValue(message.getBody(), Map.class);
                    messaging.convertAndSend("/topic/platform/broadcast", payload);
                }
                default -> log.warn("Unknown Redis channel: {}", channel);
            }
        } catch (Exception e) {
            log.error("Redis message processing failed on channel {}", channel, e);
        }
    }
}
