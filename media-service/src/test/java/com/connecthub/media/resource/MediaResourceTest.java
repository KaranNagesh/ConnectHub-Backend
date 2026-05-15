package com.connecthub.media.resource;

import com.connecthub.media.entity.MediaFile;
import com.connecthub.media.service.MediaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaResourceTest {

    @Mock private MediaService svc;
    @InjectMocks private MediaResource resource;

    @Test
    void upload_returns201WithBody() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hi".getBytes());
        MediaFile mf = MediaFile.builder().mediaId("id1").build();
        when(svc.upload(any(), eq(1), eq("room1"), any())).thenReturn(mf);

        ResponseEntity<MediaFile> resp = resource.upload(file, 1, "FREE", "room1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isEqualTo(mf);
    }

    @Test
    void upload_nullTier_normalizesToFree() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hi".getBytes());
        MediaFile mf = MediaFile.builder().mediaId("id1").build();
        when(svc.upload(any(), eq(1), eq("room1"), eq("FREE"))).thenReturn(mf);

        ResponseEntity<MediaFile> resp = resource.upload(file, 1, null, "room1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void get_found_returns200() {
        MediaFile mf = MediaFile.builder().mediaId("id1").build();
        when(svc.getById("id1")).thenReturn(Optional.of(mf));

        ResponseEntity<MediaFile> resp = resource.get("id1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(mf);
    }

    @Test
    void get_notFound_returns404() {
        when(svc.getById("ghost")).thenReturn(Optional.empty());

        ResponseEntity<MediaFile> resp = resource.get("ghost");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void byRoom_returns200WithList() {
        List<MediaFile> files = List.of(MediaFile.builder().mediaId("a").build());
        when(svc.getByRoom("room1")).thenReturn(files);

        ResponseEntity<List<MediaFile>> resp = resource.byRoom("room1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(files);
    }

    @Test
    void del_returns204() {
        doNothing().when(svc).delete("id1");

        ResponseEntity<Void> resp = resource.del("id1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(svc).delete("id1");
    }

    @Test
    void count_returns200WithCount() {
        when(svc.count("room1")).thenReturn(5);

        ResponseEntity<Integer> resp = resource.count("room1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(5);
    }

    @Test
    void downloadUrl_returnsUrlAndExpiry() {
        when(svc.createDownloadUrl("id1")).thenReturn("https://signed.example/file");

        ResponseEntity<Map<String, Object>> resp = resource.downloadUrl("id1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("url", "https://signed.example/file");
        assertThat(resp.getBody()).containsEntry("expiresInHours", 24);
    }

    @Test
    void adminAnalytics_forbiddenForNonAdmin() {
        ResponseEntity<Map<String, Object>> resp = resource.adminAnalytics("USER");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(svc);
    }

    @Test
    void adminAnalytics_returnsAnalyticsForPlatformAdmin() {
        Map<String, Object> analytics = Map.of("totalFiles", 4L);
        when(svc.adminAnalytics()).thenReturn(analytics);

        ResponseEntity<Map<String, Object>> resp = resource.adminAnalytics("PLATFORM_ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(analytics);
    }
}
