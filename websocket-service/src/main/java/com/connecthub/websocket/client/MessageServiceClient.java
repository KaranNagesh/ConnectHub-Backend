package com.connecthub.websocket.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "message-service")
public interface MessageServiceClient {

    @PostMapping("/api/v1/messages")
    Map<String, Object> persistMessage(
            @RequestBody Map<String, Object> body,
            @org.springframework.web.bind.annotation.RequestHeader("X-User-Id") String userId,
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Subscription-Tier", required = false) String subscriptionTier);
}
