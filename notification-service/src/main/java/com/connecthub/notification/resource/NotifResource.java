package com.connecthub.notification.resource;
import com.connecthub.notification.dto.SendNotificationRequest;
import com.connecthub.notification.entity.Notification;
import com.connecthub.notification.service.NotifService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/v1/notifications") @RequiredArgsConstructor
@Tag(name = "Notifications", description = "In-app notification management")
public class NotifResource {
    private final NotifService svc;
    @PostMapping public ResponseEntity<Notification> send(@RequestBody SendNotificationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(svc.send(request.toEntity()));
    }
    @GetMapping("/user/{uid}") public ResponseEntity<List<Notification>> get(@PathVariable int uid) { return ResponseEntity.ok(svc.getByRecipient(uid)); }
    @PutMapping("/{id}/read") public ResponseEntity<Void> read(@PathVariable int id) { svc.markRead(id); return ResponseEntity.noContent().build(); }
    @PutMapping("/user/{uid}/read-all") public ResponseEntity<Void> readAll(@PathVariable int uid) { svc.markAllRead(uid); return ResponseEntity.noContent().build(); }
    @GetMapping("/user/{uid}/unread-count") public ResponseEntity<Integer> unread(@PathVariable int uid) { return ResponseEntity.ok(svc.unreadCount(uid)); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> del(@PathVariable int id) { svc.delete(id); return ResponseEntity.noContent().build(); }
}
