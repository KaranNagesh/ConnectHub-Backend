package com.connecthub.websocket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

/**
 * AppConfig — Async Executor Thread Pool for WebSocket Service
 *
 * PURPOSE:
 *   Provides the "asyncExecutor" bean used by @Async-annotated methods throughout
 *   the websocket-service. This dedicated thread pool ensures that async operations
 *   (member lookup, offline message queuing, room timestamp updates) never block
 *   the STOMP message broker's I/O threads.
 *
 * WHY A SEPARATE EXECUTOR:
 *   Spring's default async executor shares threads with other tasks. By defining
 *   a named executor ("asyncExecutor"), we isolate WebSocket async work so a slow
 *   Feign call or Redis operation cannot starve the main request-handling threads.
 *   Thread names are prefixed "ws-async-" for easy identification in thread dumps.
 *
 * POOL SIZING:
 *   - corePoolSize=4:   always-ready threads for normal load
 *   - maxPoolSize=16:   max threads for burst traffic
 *   - queueCapacity=500: tasks queue here when all threads are busy rather than
 *     being rejected; 500 tasks provides enough buffer for message delivery bursts
 *
 * METHODS USING THIS EXECUTOR:
 *   - DeliveryService.deliverToRoomMembers()     — Feign call + per-member delivery
 *   - DeliveryService.updateRoomTimestamp()      — Feign call to room-service
 *   - DeliveryService.persistLastRead()          — Feign call to room-service
 *   - DeliveryService.flushPendingMessagesWithDelay() — 800ms sleep + Redis drain
 */
@Configuration
public class AppConfig {

    /**
     * asyncExecutor — the named thread pool bean referenced by @Async("asyncExecutor").
     * Annotated with name "asyncExecutor" so Spring dispatches @Async calls to this
     * specific pool rather than the default SimpleAsyncTaskExecutor.
     */
    @Bean(name = "asyncExecutor")
    public Executor asyncExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(4); e.setMaxPoolSize(16); e.setQueueCapacity(500);
        e.setThreadNamePrefix("ws-async-"); e.initialize(); return e;
    }
}
