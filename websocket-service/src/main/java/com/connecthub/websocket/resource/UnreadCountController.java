package com.connecthub.websocket.resource;

import com.connecthub.websocket.service.UnreadCountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ws/unread")
@RequiredArgsConstructor
public class UnreadCountController {

    private final UnreadCountService unreadCountService;

    @GetMapping("/{userId}")
    public ResponseEntity<Map<String, Long>> getUnreadCounts(
            @PathVariable int userId,
            @RequestHeader("X-User-Id") int authenticatedUserId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        if (userId != authenticatedUserId && !isAdmin(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(unreadCountService.getAllForUser(userId));
    }

    private boolean isAdmin(String role) {
        return "ADMIN".equals(role) || "PLATFORM_ADMIN".equals(role);
    }
}
