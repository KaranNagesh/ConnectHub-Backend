package com.connecthub.presence.resource;
import com.connecthub.presence.dto.UserPresence;
import com.connecthub.presence.service.PresenceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/presence") @RequiredArgsConstructor
@Tag(name = "Presence", description = "Online status tracking")
public class PresenceResource {
    private final PresenceService svc;
    @PostMapping("/online/{uid}") public ResponseEntity<Void> online(@PathVariable int uid, @RequestBody Map<String,String> b) { svc.setOnline(uid, b.get("deviceType"), b.get("sessionId")); return ResponseEntity.ok().build(); }
    @PostMapping("/offline/{uid}") public ResponseEntity<Void> offline(@PathVariable int uid) { svc.setOffline(uid); return ResponseEntity.ok().build(); }
    @PutMapping("/status/{uid}") public ResponseEntity<Void> status(@PathVariable int uid, @RequestBody Map<String,String> b) { svc.updateStatus(uid, b.get("status"), b.get("customMessage")); return ResponseEntity.noContent().build(); }
    @GetMapping("/{uid}") public ResponseEntity<UserPresence> get(@PathVariable int uid) { return svc.get(uid).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build()); }
    @PostMapping("/bulk") public ResponseEntity<List<UserPresence>> bulk(@RequestBody List<Integer> ids) { return ResponseEntity.ok(svc.getBulk(ids)); }
    @GetMapping("/online/count") public ResponseEntity<Integer> count() { return ResponseEntity.ok(svc.onlineCount()); }
    @GetMapping("/online/users") public ResponseEntity<List<Integer>> onlineUsers() { return ResponseEntity.ok(svc.getOnlineUserIds()); }
    @GetMapping("/{uid}/check") public ResponseEntity<Boolean> check(@PathVariable int uid) { return ResponseEntity.ok(svc.isOnline(uid)); }
    @PostMapping("/ping/{uid}") public ResponseEntity<Void> ping(@PathVariable int uid) { svc.ping(uid); return ResponseEntity.ok().build(); }
}
