package com.connecthub.media.resource;

import com.connecthub.media.config.MediaTierLimits;
import com.connecthub.media.entity.MediaFile;
import com.connecthub.media.service.MediaService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
@Tag(name = "Media", description = "File upload and serving (Amazon S3)")
public class MediaResource {

    private final MediaService svc;

    @PostMapping("/upload")
    public ResponseEntity<MediaFile> upload(@RequestParam("file") MultipartFile file,
                                             @RequestHeader("X-User-Id") int uid,
                                             @RequestHeader(value = "X-Subscription-Tier", required = false) String subscriptionTier,
                                             @RequestParam String roomId) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(svc.upload(file, uid, roomId, MediaTierLimits.normalizeTier(subscriptionTier)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MediaFile> get(@PathVariable String id) {
        return svc.getById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/download-url")
    public ResponseEntity<Map<String, Object>> downloadUrl(@PathVariable String id) {
        return ResponseEntity.ok(Map.of(
                "url", svc.createDownloadUrl(id),
                "expiresInHours", 24
        ));
    }

    @GetMapping("/room/{roomId}")
    public ResponseEntity<List<MediaFile>> byRoom(@PathVariable String roomId) {
        return ResponseEntity.ok(svc.getByRoom(roomId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> del(@PathVariable String id) {
        svc.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/room/{roomId}/count")
    public ResponseEntity<Integer> count(@PathVariable String roomId) {
        return ResponseEntity.ok(svc.count(roomId));
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
