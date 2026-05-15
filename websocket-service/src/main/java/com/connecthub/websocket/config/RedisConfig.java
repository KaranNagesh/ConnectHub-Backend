package com.connecthub.websocket.config;

import com.connecthub.websocket.event.RedisMessageSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.*;

/**
 * RedisConfig — Redis Pub/Sub Channel Definitions and Listener Registration
 *
 * PURPOSE:
 *   Defines the Redis pub/sub channel names used for cross-pod event broadcasting
 *   and registers RedisMessageSubscriber as the listener for all of them.
 *
 * WHY REDIS PUB/SUB FOR WEBSOCKET FAN-OUT:
 *   When multiple websocket-service pods are running (horizontal scaling), a message
 *   sent by a user on pod A must reach users connected to pods B and C. Pods cannot
 *   call each other directly. Instead, pod A publishes to a Redis channel; all pods
 *   subscribe to that channel and each forwards the event to their own STOMP clients.
 *
 * CHANNEL REGISTRY:
 *   Each constant is the Redis channel name string, shared between publishers
 *   (ChatWebSocketHandler, DeliveryService) and the subscriber (RedisMessageSubscriber).
 *   Using constants prevents typo mismatches between publisher and subscriber code.
 *
 *   CHAT_CHANNEL     ("chat:messages")      — new chat messages → /topic/room/{roomId}
 *   PRESENCE_CHANNEL ("chat:presence")      — online/offline events → /topic/presence
 *   EDIT_CHANNEL     ("chat:edits")         — message edits → /topic/room/{roomId}/edit
 *   DELETE_CHANNEL   ("chat:deletes")       — message deletions → /topic/room/{roomId}/delete
 *   REACTION_CHANNEL ("chat:reactions")     — emoji reactions → /topic/room/{roomId}/reactions
 *   NOTIF_CHANNEL    ("chat:notifications") — user notifications → /user/{id}/queue/notifications
 *
 * LISTENER CONTAINER:
 *   RedisMessageListenerContainer is Spring Data Redis's event loop for pub/sub.
 *   It maintains a persistent subscription to all registered channels and calls
 *   the registered MessageListener (RedisMessageSubscriber.onMessage()) for each message.
 *   All six channels map to the same subscriber bean — the subscriber switches
 *   on channel name internally to route each event to the correct STOMP destination.
 */
@Configuration
public class RedisConfig {
    public static final String CHAT_CHANNEL     = "chat:messages";
    public static final String PRESENCE_CHANNEL = "chat:presence";
    public static final String EDIT_CHANNEL     = "chat:edits";
    public static final String DELETE_CHANNEL   = "chat:deletes";
    public static final String REACTION_CHANNEL = "chat:reactions";
    public static final String NOTIF_CHANNEL    = "chat:notifications";
    public static final String BROADCAST_CHANNEL = "chat:platform:broadcasts";
    public static final String BROADCAST_HISTORY_KEY = "chat:platform:broadcasts:recent";

    /**
     * container — creates the Redis pub/sub listener container and subscribes to all channels.
     * Spring manages the container lifecycle (start/stop with the application context).
     * Adding all channels to the same subscriber bean minimizes Redis connection overhead.
     */
    @Bean
    public RedisMessageListenerContainer container(RedisConnectionFactory factory, RedisMessageSubscriber sub) {
        RedisMessageListenerContainer c = new RedisMessageListenerContainer();
        c.setConnectionFactory(factory);
        c.addMessageListener(sub, new ChannelTopic(CHAT_CHANNEL));
        c.addMessageListener(sub, new ChannelTopic(PRESENCE_CHANNEL));
        c.addMessageListener(sub, new ChannelTopic(EDIT_CHANNEL));
        c.addMessageListener(sub, new ChannelTopic(DELETE_CHANNEL));
        c.addMessageListener(sub, new ChannelTopic(REACTION_CHANNEL));
        c.addMessageListener(sub, new ChannelTopic(NOTIF_CHANNEL));
        c.addMessageListener(sub, new ChannelTopic(BROADCAST_CHANNEL));
        return c;
    }
}
