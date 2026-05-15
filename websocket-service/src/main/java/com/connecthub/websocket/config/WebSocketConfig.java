package com.connecthub.websocket.config;

import com.connecthub.websocket.interceptor.JwtChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.*;

/**
 * WebSocketConfig — STOMP WebSocket Broker Configuration
 *
 * PURPOSE:
 *   Configures the Spring STOMP WebSocket server: the in-memory message broker,
 *   endpoint URL, destination prefixes, authentication interceptor, and transport
 *   limits. This is the central wiring that makes the real-time messaging work.
 *
 * MESSAGE BROKER:
 *   enableSimpleBroker("/topic", "/queue") sets up Spring's in-memory STOMP broker.
 *   - /topic destinations: fan-out (pub/sub) — everyone subscribed to a topic receives
 *     the message. Used for room messages: /topic/room/{roomId}.
 *   - /queue destinations: point-to-point — used with user-destination routing for
 *     personal queues: /user/{userId}/queue/messages, /user/{userId}/queue/notifications.
 *   setApplicationDestinationPrefixes("/app") means frames sent to "/app/..." are
 *   routed to @MessageMapping handler methods in ChatWebSocketHandler.
 *   setUserDestinationPrefix("/user") enables user-specific destinations.
 *
 * STOMP ENDPOINT:
 *   /ws is the WebSocket handshake URL. withSockJS() enables SockJS fallback for
 *   clients that don't support native WebSocket (older browsers, proxies that block
 *   WebSocket upgrades). setAllowedOriginPatterns("*") permits all origins for dev;
 *   in production this should be restricted to the app's domain.
 *
 * HEARTBEAT:
 *   The broker is configured to send and expect heartbeats every 10 seconds
 *   (setHeartbeatValue([10000, 10000])). The SockJS transport adds its own heartbeat
 *   every 25 seconds (setHeartbeatTime). Without heartbeats, firewalls and load
 *   balancers that timeout idle connections would silently drop sessions.
 *   A dedicated 2-thread ThreadPoolTaskScheduler handles the heartbeat timing.
 *
 * INBOUND CHANNEL THREAD POOL:
 *   Inbound frames go through an executor with 8 core threads, up to 32 max, and
 *   a 200-message queue. This handles concurrent messages from many connected clients
 *   without blocking the STOMP broker thread.
 *
 * TRANSPORT LIMITS:
 *   - Message size: 128 KB (prevents oversized payloads from exhausting memory)
 *   - Send buffer: 512 KB per client (frames accumulate here if the client is slow)
 *   - Send timeout: 20 seconds (disconnect slow clients that cause buffer buildup)
 */
@Configuration @EnableWebSocketMessageBroker @RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtChannelInterceptor jwtInterceptor;

    @org.springframework.beans.factory.annotation.Value("${FRONTEND_URL:http://localhost:4200}")
    private String frontendUrl;

    /**
     * configureMessageBroker — sets up the in-memory STOMP broker and destination prefixes.
     * The heartbeat scheduler is initialized manually to control thread naming
     * (ws-heartbeat-N) and avoid interference with the application's main thread pool.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2); scheduler.setThreadNamePrefix("ws-heartbeat-"); scheduler.initialize();
        registry.enableSimpleBroker("/topic", "/queue").setHeartbeatValue(new long[]{10000, 10000}).setTaskScheduler(scheduler);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    /**
     * registerStompEndpoints — registers the /ws WebSocket endpoint with SockJS fallback.
     * setSessionCookieNeeded(false) prevents SockJS from requiring a JSESSIONID cookie,
     * which is not needed since authentication is JWT-based via STOMP headers.
     * setDisconnectDelay(30000) gives clients 30 seconds to reconnect before their
     * SockJS session is treated as closed.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(frontendUrl, "http://localhost:4200", "http://127.0.0.1:4200",
                        "http://localhost:*", "http://127.0.0.1:*")
                .withSockJS()
                .setHeartbeatTime(25000).setDisconnectDelay(30000).setSessionCookieNeeded(false);
    }

    /**
     * configureClientInboundChannel — registers the JWT interceptor and sets the thread pool.
     * JwtChannelInterceptor runs on every inbound frame to authenticate CONNECT frames.
     * The thread pool (8 core, 32 max, 200 queue) handles concurrent message processing
     * without blocking the broker's internal I/O threads.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration reg) {
        reg.interceptors(jwtInterceptor);
        reg.taskExecutor().corePoolSize(8).maxPoolSize(32).queueCapacity(200);
    }

    /**
     * configureWebSocketTransport — sets size and timeout limits for the transport layer.
     * These limits protect the server from memory exhaustion caused by oversized messages
     * or slow clients whose send buffers are never drained.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration reg) {
        reg.setMessageSizeLimit(128 * 1024).setSendBufferSizeLimit(512 * 1024).setSendTimeLimit(20000);
    }
}
