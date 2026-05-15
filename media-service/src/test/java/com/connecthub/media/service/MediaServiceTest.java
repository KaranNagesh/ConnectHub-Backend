package com.connecthub.media.service;

import com.connecthub.media.entity.MediaFile;
import com.connecthub.media.exception.MediaPlanLimitException;
import com.connecthub.media.exception.MediaStorageQuotaException;
import com.connecthub.media.repository.MediaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock
    private MediaRepository repo;
    @Mock
    private S3Client s3Client;
    @Mock
    private MediaUploadRateLimiter uploadRateLimiter;
    @InjectMocks
    private MediaService mediaService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(mediaService, "bucketName", "test-bucket");
        ReflectionTestUtils.setField(mediaService, "region", "us-east-1");
    }

    // ── upload ──────────────────────────────────────────────────────────────

    @Test
    void upload_textFile_success() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.txt", "text/plain", "hello world".getBytes());
        when(uploadRateLimiter.tryAcquire(any(), any())).thenReturn(true);
        when(repo.sumSizeKbByUploaderId(1)).thenReturn(0L);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        MediaFile saved = MediaFile.builder().mediaId("abc")
                .url("https://test-bucket.s3.us-east-1.amazonaws.com/files/uuid/doc.txt").build();
        when(repo.save(any())).thenReturn(saved);

        MediaFile result = mediaService.upload(file, 1, "room1", "FREE");

        assertThat(result.getMediaId()).isEqualTo("abc");
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(repo).save(any());
    }

    @Test
    void upload_imageFile_thumbnailFailureIsNonFatal() throws IOException {
        // Invalid JPEG bytes — Thumbnailator will throw, but upload proceeds
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", "not-a-real-jpeg".getBytes());
        when(uploadRateLimiter.tryAcquire(any(), any())).thenReturn(true);
        when(repo.sumSizeKbByUploaderId(1)).thenReturn(0L);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MediaFile result = mediaService.upload(file, 1, "room1", "FREE");

        assertThat(result).isNotNull();
        verify(s3Client, atLeastOnce()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void upload_rateLimitExceeded_throws() {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
        when(uploadRateLimiter.tryAcquire(any(), any())).thenReturn(false);

        assertThrows(MediaPlanLimitException.class,
                () -> mediaService.upload(file, 1, "room1", "FREE"));
        verify(repo, never()).save(any());
    }

    @Test
    void upload_emptyFile_throws() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        when(uploadRateLimiter.tryAcquire(any(), any())).thenReturn(true);

        assertThrows(RuntimeException.class,
                () -> mediaService.upload(file, 1, "room1", "FREE"));
    }

    @Test
    void upload_fileTooLarge_throws() {
        byte[] big = new byte[26 * 1024 * 1024]; // Requirement cap is 25MB
        MockMultipartFile file = new MockMultipartFile("file", "big.txt", "text/plain", big);
        when(uploadRateLimiter.tryAcquire(any(), any())).thenReturn(true);

        assertThrows(RuntimeException.class,
                () -> mediaService.upload(file, 1, "room1", "FREE"));
    }

    @Test
    void upload_storageQuotaExceeded_throws() {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());
        when(uploadRateLimiter.tryAcquire(any(), any())).thenReturn(true);
        // FREE cap is 100MB (102400 KB); return 102400 so adding even 1KB exceeds it
        when(repo.sumSizeKbByUploaderId(1)).thenReturn(102400L);

        assertThrows(MediaStorageQuotaException.class,
                () -> mediaService.upload(file, 1, "room1", "FREE"));
    }

    @Test
    void upload_proTierStorageQuotaExceeded_throws() {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());
        when(uploadRateLimiter.tryAcquire(any(), any())).thenReturn(true);
        // PRO cap is 10GB (10485760 KB)
        when(repo.sumSizeKbByUploaderId(1)).thenReturn(10485760L);

        assertThrows(MediaStorageQuotaException.class,
                () -> mediaService.upload(file, 1, "room1", "PRO"));
    }

    @Test
    void upload_disallowedContentType_throws() {
        MockMultipartFile file = new MockMultipartFile("file", "virus.exe", "application/octet-stream",
                "MZ".getBytes());
        when(uploadRateLimiter.tryAcquire(any(), any())).thenReturn(true);
        when(repo.sumSizeKbByUploaderId(1)).thenReturn(0L);

        assertThrows(RuntimeException.class,
                () -> mediaService.upload(file, 1, "room1", "FREE"));
    }

    @Test
    void upload_nullContentType_throws() {
        MockMultipartFile file = new MockMultipartFile("file", "f.bin", null, "data".getBytes());
        when(uploadRateLimiter.tryAcquire(any(), any())).thenReturn(true);
        when(repo.sumSizeKbByUploaderId(1)).thenReturn(0L);

        assertThrows(RuntimeException.class,
                () -> mediaService.upload(file, 1, "room1", "FREE"));
    }

    @Test
    void upload_filenameWithSpecialChars_sanitized() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "my file (1).txt", "text/plain", "content".getBytes());
        when(uploadRateLimiter.tryAcquire(any(), any())).thenReturn(true);
        when(repo.sumSizeKbByUploaderId(1)).thenReturn(0L);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MediaFile result = mediaService.upload(file, 1, "room1", "FREE");

        // Sanitized name should not contain spaces or parentheses
        assertThat(result.getOriginalName()).doesNotContain(" ", "(", ")");
    }

    @Test
    void upload_nullFilename_usesDefault() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", null, "text/plain", "content".getBytes());
        when(uploadRateLimiter.tryAcquire(any(), any())).thenReturn(true);
        when(repo.sumSizeKbByUploaderId(1)).thenReturn(0L);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MediaFile result = mediaService.upload(file, 1, "room1", "FREE");
        assertThat(result).isNotNull();
    }

    // ── getById ─────────────────────────────────────────────────────────────

    @Test
    void getById_found_returnsPresent() {
        MediaFile mf = MediaFile.builder().mediaId("id1").build();
        when(repo.findById("id1")).thenReturn(Optional.of(mf));

        Optional<MediaFile> result = mediaService.getById("id1");

        assertThat(result).isPresent().contains(mf);
    }

    @Test
    void getById_notFound_returnsEmpty() {
        when(repo.findById("missing")).thenReturn(Optional.empty());

        assertThat(mediaService.getById("missing")).isEmpty();
    }

    // ── getByRoom ────────────────────────────────────────────────────────────

    @Test
    void getByRoom_returnsList() {
        List<MediaFile> files = List.of(MediaFile.builder().mediaId("a").build());
        when(repo.findByRoomIdOrderByUploadedAtDesc("room1")).thenReturn(files);

        assertThat(mediaService.getByRoom("room1")).isEqualTo(files);
    }

    // ── count ────────────────────────────────────────────────────────────────

    @Test
    void count_returnsRepoValue() {
        when(repo.countByRoomId("room1")).thenReturn(7);

        assertThat(mediaService.count("room1")).isEqualTo(7);
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    void delete_withThumbnail_deletesMainAndThumb() {
        MediaFile mf = MediaFile.builder()
                .mediaId("id1")
                .filename("images/uuid/photo.jpg")
                .originalName("photo.jpg")
                .thumbnailUrl("https://bucket/images/uuid/thumb_photo.jpg")
                .build();
        when(repo.findById("id1")).thenReturn(Optional.of(mf));
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenReturn(DeleteObjectResponse.builder().build());

        mediaService.delete("id1");

        verify(s3Client, times(2)).deleteObject(any(DeleteObjectRequest.class));
        verify(repo).delete(mf);
    }

    @Test
    void delete_withoutThumbnail_deletesOnlyMain() {
        MediaFile mf = MediaFile.builder()
                .mediaId("id1")
                .filename("files/uuid/doc.pdf")
                .originalName("doc.pdf")
                .thumbnailUrl(null)
                .build();
        when(repo.findById("id1")).thenReturn(Optional.of(mf));
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenReturn(DeleteObjectResponse.builder().build());

        mediaService.delete("id1");

        verify(s3Client, times(1)).deleteObject(any(DeleteObjectRequest.class));
        verify(repo).delete(mf);
    }

    @Test
    void delete_notFound_noOp() {
        when(repo.findById("ghost")).thenReturn(Optional.empty());

        mediaService.delete("ghost");

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(repo, never()).delete(any());
    }

    @Test
    void delete_s3Fails_stillDeletesFromDb() {
        MediaFile mf = MediaFile.builder()
                .mediaId("id1")
                .filename("files/uuid/doc.pdf")
                .originalName("doc.pdf")
                .thumbnailUrl(null)
                .build();
        when(repo.findById("id1")).thenReturn(Optional.of(mf));
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(new RuntimeException("S3 error"));

        mediaService.delete("id1");

        verify(repo).delete(mf);
    }
}
