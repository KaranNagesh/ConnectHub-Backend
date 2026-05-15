package com.connecthub.websocket.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;

@FeignClient(name = "presence-service")
public interface PresenceServiceClient {

    @PostMapping("/api/v1/presence/online/{uid}")
    void markOnline(@PathVariable("uid") String uid, @RequestBody Map<String, String> body, @RequestHeader("X-User-Id") String userId);

    @PostMapping("/api/v1/presence/offline/{uid}")
    void markOffline(@PathVariable("uid") String uid, @RequestHeader("X-User-Id") String userId);

    @GetMapping("/api/v1/presence/online/users")
    List<Integer> getOnlineUserIds(@RequestHeader("X-User-Id") String userId);
}
