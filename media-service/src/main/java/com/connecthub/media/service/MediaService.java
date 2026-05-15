package com.connecthub.media.service;

import com.connecthub.media.config.MediaTierLimits;
import com.connecthub.media.entity.MediaFile;
import com.connecthub.media.exception.MediaPlanLimitException;
import com.connecthub.media.exception.MediaStorageQuotaException;
import com.connecthub.media.repository.MediaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * MediaService — File Upload, Storage, and Retrieval via AWS S3
 *
 * PURPOSE:
 *   Handles all media file operations for the chat: uploading images and documents
 *   to S3, generating image thumbnails, enforcing per-user storage quotas, and
 *   deleting files from both S3 and the database. The resulting S3 URL is stored
 *   in the message's mediaUrl field so the frontend can render inline images and
 *   downloadable file attachments.
 *
 * UPLOAD FLOW (upload() method):
 *   1. RATE LIMIT: Check the per-minute upload rate limit for the user's tier via
 *      MediaUploadRateLimiter (Redis INCR counter). Reject with MediaPlanLimitException if exceeded.
 *   2. BASIC VALIDATION: Reject empty files and files exceeding 25MB.
 *   3. STORAGE QUOTA: Sum the user's existing stored KB from the DB and compare against
 *      their tier's total storage cap (MediaTierLimits). Reject with MediaStorageQuotaException
 *      if the upload would exceed their quota.
 *   4. CONTENT TYPE CHECK: Only allow files matching the ALLOWED MIME types set.
 *      Reject unknown or dangerous types (e.g., .exe, .sh).
 *   5. S3 UPLOAD: Write the file to a temp path, upload to S3 using the AWS SDK.
 *      S3 key format: "{images|files}/{uuid}/{sanitized-filename}". UUID ensures
 *      key uniqueness even for files with identical names.
 *   6. THUMBNAIL GENERATION: For image types, generate a 300x300 JPEG thumbnail using
 *      Thumbnailator and upload it to S3 alongside the original. Thumbnail failures
 *      are non-fatal — the upload proceeds without a thumbnail URL.
 *   7. PERSIST: Save a MediaFile row to MySQL with the S3 URL, thumbnail URL,
 *      original filename, MIME type, and size in KB.
 *   8. CLEANUP: Always delete the local temp files in the finally block, even on failure.
 *
 * S3 KEY STRUCTURE:
 *   - Main file:  "images/{uuid}/{filename}" or "files/{uuid}/{filename}"
 *   - Thumbnail:  "images/{uuid}/thumb_{filename}"
 *   The sub-directory (images vs files) is chosen based on MIME type — images go
 *   in "images/", everything else goes in "files/".
 *
 * STORAGE QUOTA:
 *   MediaTierLimits.storageCapKb() returns the cap in kilobytes for each tier.
 *   The running total is computed by repo.sumSizeKbByUploaderId(), which sums
 *   all sizeKb values for the uploader. Incoming file size is rounded up to at
 *   least 1 KB to prevent rounding exploits with tiny files.
 *
 * FILE SANITIZATION:
 *   The original filename has all non-alphanumeric characters (except . _ -)
 *   replaced with underscores before being used as the S3 key. This prevents
 *   path traversal attacks and special character issues in S3 object keys.
 *
 * DELETION:
 *   delete() removes both the main file and its thumbnail (if any) from S3 before
 *   deleting the database row. The thumbnail key is derived from the main key by
 *   inserting "thumb_" before the filename portion.
 */
@SuppressWarnings("null")
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class MediaService {

    private final MediaRepository repo;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final MediaUploadRateLimiter uploadRateLimiter;

    @Value("${aws.s3.bucket:connecthub-media-bucket}")
    private String bucketName;

    @Value("${aws.region:us-east-1}")
    private String region;

    @Value("${media.temp-dir:}")
    private String mediaTempDir;

    /*
     * Image MIME types that receive thumbnail generation.
     * Non-image ALLOWED types (PDF, Word, ZIP, text) are stored as-is without thumbnails.
     */
    private static final Set<String> IMAGES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    /*
     * Whitelist of permitted MIME types. Any content-type not in this set is rejected.
     * This prevents upload of potentially dangerous file types.
     */
    private static final Set<String> ALLOWED = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp",
            "application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/zip", "text/plain");

    /*
     * Application-level 25MB file size cap. Spring's multipart config also enforces
     * this at the framework level, but this provides a second line of defense.
     */
    private static final long MAX_SIZE = 25L * 1024L * 1024L;

    /**
     * upload — validates, uploads to S3, generates a thumbnail if applicable, and persists the media record.
     *
     * @param file             the multipart file from the HTTP request
     * @param uploaderId       the authenticated user's ID (from X-User-Id header)
     * @param roomId           the room this file is being shared in
     * @param subscriptionTier the user's tier for rate limit and quota lookups
     * @return the persisted MediaFile record with S3 URL and thumbnail URL
     * @throws IOException if temp file creation or S3 upload fails
     */
    public MediaFile upload(MultipartFile file, int uploaderId, String roomId, String subscriptionTier) throws IOException {
        String tier = MediaTierLimits.normalizeTier(subscriptionTier);
        if (!uploadRateLimiter.tryAcquire(String.valueOf(uploaderId), tier)) {
            throw new MediaPlanLimitException("Upload rate limit exceeded for your plan");
        }
        if (file.isEmpty()) throw new RuntimeException("Empty file");
        if (file.getSize() > MAX_SIZE) throw new RuntimeException("File exceeds 25MB limit");

        long capKb = MediaTierLimits.storageCapKb(tier);
        long usedKb = repo.sumSizeKbByUploaderId(uploaderId);
        long incomingKb = Math.max(1L, file.getSize() / 1024L);
        if (usedKb + incomingKb > capKb) {
            String capHuman = capKb >= 1024L * 1024L
                    ? (capKb / (1024L * 1024L)) + " GB"
                    : (capKb / 1024L) + " MB";
            throw new MediaStorageQuotaException(
                    "Storage quota exceeded for your plan (limit " + capHuman + " total). Delete files or upgrade to PRO.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED.contains(contentType))
            throw new RuntimeException("File type not allowed: " + contentType);

        /*
         * Sanitize the filename by replacing all non-safe characters with underscores.
         * This prevents path traversal and special character issues in S3 object keys.
         */
        String originalName = file.getOriginalFilename() != null
                ? file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_") : "file";
        String uuid = UUID.randomUUID().toString();
        String subDir = IMAGES.contains(contentType) ? "images" : "files";
        String s3Key = subDir + "/" + uuid + "/" + originalName;

        /*
         * Write to a temp file so we can pass a File reference to both the S3 SDK
         * (which needs a seekable stream for upload) and Thumbnailator (which needs
         * a file path for efficient image processing).
         */
        Path tempFile = Files.createTempFile(mediaTempDirectory(), "upload_", originalName);
        file.transferTo(tempFile.toFile());

        try {
            s3Client.putObject(PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(s3Key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromFile(tempFile.toFile()));

            String url = String.format("https://%s.s3.%s.amazonaws.com/%s", bucketName, region, s3Key);

            /*
             * Thumbnail generation: for images, create a 300x300 JPEG using Thumbnailator
             * and upload it under the same UUID directory as "thumb_{filename}".
             * Failures here are non-fatal — the upload proceeds with thumbnailUrl=null.
             */
            String thumbnailUrl = null;
            if (IMAGES.contains(contentType)) {
                Path tempThumb = null;
                try {
                    String thumbName = "thumb_" + originalName;
                    String thumbKey = subDir + "/" + uuid + "/" + thumbName;
                    tempThumb = Files.createTempFile(mediaTempDirectory(), "thumb_", thumbName);

                    Thumbnails.of(tempFile.toFile()).size(300, 300).outputFormat("jpg").toFile(tempThumb.toFile());

                    s3Client.putObject(PutObjectRequest.builder()
                                    .bucket(bucketName)
                                    .key(thumbKey)
                                    .contentType("image/jpeg")
                                    .build(),
                            RequestBody.fromFile(tempThumb.toFile()));

                    thumbnailUrl = String.format("https://%s.s3.%s.amazonaws.com/%s", bucketName, region, thumbKey);
                } catch (Exception e) {
                    log.warn("Thumbnail generation failed: {}", e.getMessage());
                } finally {
                    if (tempThumb != null) Files.deleteIfExists(tempThumb);
                }
            }

            MediaFile media = MediaFile.builder()
                    .uploaderId(uploaderId).roomId(roomId)
                    .filename(s3Key)
                    .originalName(originalName)
                    .url(url).thumbnailUrl(thumbnailUrl)
                    .mimeType(contentType)
                    .sizeKb(file.getSize() / 1024)
                    .build();

            log.info("File uploaded to S3: {} ({} KB) by user {}", originalName, media.getSizeKb(), uploaderId);
            return repo.save(media);

        } finally {
            /*
             * Always clean up the local temp file regardless of success or failure.
             * Without this, failed uploads would leave orphaned files in the OS temp directory.
             */
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * getById — retrieves a single media file record by its database ID.
     * Used when the frontend needs metadata about a specific file (e.g., for a download link).
     */
    @Transactional(readOnly = true)
    public Optional<MediaFile> getById(String id) { return repo.findById(id); }

    /**
     * getByRoom — returns all media files shared in a room, newest first.
     * Used by the media gallery feature that shows all shared files/images in a sidebar panel.
     */
    @Transactional(readOnly = true)
    public List<MediaFile> getByRoom(String roomId) { return repo.findByRoomIdOrderByUploadedAtDesc(roomId); }

    @Transactional(readOnly = true)
    public String createDownloadUrl(String id) {
        MediaFile media = repo.findById(id).orElseThrow(() -> new RuntimeException("Media not found"));
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(media.getFilename())
                .responseContentDisposition("attachment; filename=\"" + media.getOriginalName() + "\"")
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofHours(24))
                .getObjectRequest(getObjectRequest)
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    @Transactional(readOnly = true)
    public java.util.Map<String, Object> adminAnalytics() {
        long totalFiles = repo.count();
        long totalStorageKb = repo.sumSizeKb();
        return java.util.Map.of(
                "totalFiles", totalFiles,
                "storageUsedKb", totalStorageKb,
                "storageUsedMb", Math.round((totalStorageKb / 1024.0) * 100.0) / 100.0
        );
    }

    /**
     * delete — removes a media file from S3 and from the database.
     *
     * HOW IT WORKS:
     *   1. Load the MediaFile record to get the S3 key (stored in the filename field).
     *   2. Delete the main file from S3.
     *   3. If a thumbnail exists (thumbnailUrl is not null), derive its S3 key by
     *      replacing the filename with "thumb_{filename}" and delete it too.
     *   4. Delete the database row.
     *   S3 deletion failures are caught and logged — the DB row is still deleted so
     *   orphaned S3 objects can be cleaned up separately if needed.
     */
    public void delete(String id) {
        repo.findById(id).ifPresent(f -> {
            try {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(f.getFilename())
                        .build());

                if (f.getThumbnailUrl() != null) {
                    String thumbKey = f.getFilename().replace(f.getOriginalName(), "thumb_" + f.getOriginalName());
                    s3Client.deleteObject(DeleteObjectRequest.builder()
                            .bucket(bucketName)
                            .key(thumbKey)
                            .build());
                }
            } catch (Exception e) {
                log.warn("Failed to delete file from S3: {}", e.getMessage());
            }
            repo.delete(f);
        });
    }

    /**
     * count — returns the number of media files in a room.
     * Used by the room info panel to display the file/media count badge.
     */
    @Transactional(readOnly = true)
    public int count(String roomId) { return repo.countByRoomId(roomId); }

    private Path mediaTempDirectory() throws IOException {
        Path directory = mediaTempDir == null || mediaTempDir.isBlank()
                ? Path.of(System.getProperty("user.home"), ".connecthub", "media-tmp")
                : Path.of(mediaTempDir);
        Files.createDirectories(directory);
        return directory;
    }
}
