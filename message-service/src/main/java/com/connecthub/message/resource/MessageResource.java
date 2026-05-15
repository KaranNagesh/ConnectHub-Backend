package com.connecthub.message.resource;

import com.connecthub.message.config.SubscriptionTierLimits;
import com.connecthub.message.dto.SendMessageRequest;
import com.connecthub.message.entity.*;
import com.connecthub.message.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/messages")
@RequiredArgsConstructor
@Tag(name = "Messages", description = "Chat message lifecycle")
public class MessageResource {
	private final MessageService svc;

	@PostMapping
	public ResponseEntity<Message> send(@RequestBody SendMessageRequest request,
			@RequestHeader(value = "X-Subscription-Tier", required = false) String subscriptionTier) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(svc.send(request.toEntity(), SubscriptionTierLimits.normalizeTier(subscriptionTier)));
	}

	@GetMapping("/room/{roomId}")
	@Operation(summary = "Get messages (cursor-based)", description = "Pass ?before={sentAt} for pagination, ?limit= for page size")
	public ResponseEntity<List<Message>> get(@PathVariable String roomId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime before,
			@RequestParam(defaultValue = "50") int limit) {
		return ResponseEntity.ok(svc.getMessages(roomId, before, limit));
	}

	@PutMapping("/{id}")
	public ResponseEntity<Message> edit(@PathVariable String id, @RequestHeader("X-User-Id") int uid,
			@RequestBody Map<String, String> b) {
		return ResponseEntity.ok(svc.edit(id, b.get("content"), uid));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable String id, @RequestHeader("X-User-Id") int uid) {
		svc.delete(id, uid);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/room/{roomId}/search")
	public ResponseEntity<List<Message>> search(@PathVariable String roomId, @RequestParam String keyword) {
		return ResponseEntity.ok(svc.search(roomId, keyword));
	}

	@PutMapping("/{id}/status")
	public ResponseEntity<Void> status(@PathVariable String id, @RequestBody Map<String, String> b) {
		svc.updateStatus(id, b.get("status"));
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/room/{roomId}/unread")
	public ResponseEntity<Long> unread(@PathVariable String roomId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime lastReadAt) {
		return ResponseEntity.ok(svc.unreadCount(roomId, lastReadAt));
	}

	@DeleteMapping("/room/{roomId}/clear")
	public ResponseEntity<Void> clear(@PathVariable String roomId) {
		svc.clearHistory(roomId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/reactions")
	public ResponseEntity<MessageReaction> react(@PathVariable String id, @RequestHeader("X-User-Id") int uid,
			@RequestBody Map<String, String> b) {
		return ResponseEntity.status(HttpStatus.CREATED).body(svc.addReaction(id, uid, b.get("emoji")));
	}

	@DeleteMapping("/{id}/reactions")
	public ResponseEntity<Void> unreact(@PathVariable String id, @RequestHeader("X-User-Id") int uid,
			@RequestParam String emoji) {
		svc.removeReaction(id, uid, emoji);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/{id}/reactions")
	public ResponseEntity<List<MessageReaction>> reactions(@PathVariable String id) {
		return ResponseEntity.ok(svc.getReactions(id));
	}

	@GetMapping("/admin/analytics")
	public ResponseEntity<Map<String, Object>> adminAnalytics(@RequestHeader(value = "X-User-Role", required = false) String role) {
		if (!isAdmin(role)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		return ResponseEntity.ok(svc.adminAnalytics());
	}

	private boolean isAdmin(String role) {
		return "ADMIN".equals(role) || "PLATFORM_ADMIN".equals(role);
	}
}
