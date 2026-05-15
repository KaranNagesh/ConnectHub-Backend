package com.connecthub.notification.resource;

import com.connecthub.notification.entity.DeviceToken;
import com.connecthub.notification.repository.DeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications/devices")
@RequiredArgsConstructor
public class DeviceTokenResource {

    private final DeviceTokenRepository repo;

    @PostMapping
    public ResponseEntity<DeviceToken> register(
            @RequestHeader("X-User-Id") int userId,
            @RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token == null || token.isBlank()) return ResponseEntity.badRequest().build();

        DeviceToken deviceToken = repo.findByToken(token.trim())
                .orElseGet(DeviceToken::new);
        deviceToken.setUserId(userId);
        deviceToken.setToken(token.trim());
        deviceToken.setPlatform(body.getOrDefault("platform", "WEB"));
        deviceToken.setLastSeenAt(LocalDateTime.now());
        return ResponseEntity.ok(repo.save(deviceToken));
    }

    @DeleteMapping
    public ResponseEntity<Void> unregister(
            @RequestHeader("X-User-Id") int userId,
            @RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token != null && !token.isBlank()) repo.deleteByUserIdAndToken(userId, token.trim());
        return ResponseEntity.noContent().build();
    }
}
