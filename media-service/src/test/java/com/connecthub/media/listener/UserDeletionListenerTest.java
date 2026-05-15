package com.connecthub.media.listener;

import com.connecthub.media.entity.MediaFile;
import com.connecthub.media.repository.MediaRepository;
import com.connecthub.media.service.MediaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserDeletionListenerTest {

    @Mock private MediaRepository mediaRepository;
    @Mock private MediaService mediaService;
    @InjectMocks private UserDeletionListener listener;

    @Test
    void onUserDeleted_deletesAllUserFiles() {
        MediaFile f1 = MediaFile.builder().mediaId("m1").build();
        MediaFile f2 = MediaFile.builder().mediaId("m2").build();
        when(mediaRepository.findByUploaderId(42)).thenReturn(List.of(f1, f2));

        listener.onUserDeleted("42");

        verify(mediaService).delete("m1");
        verify(mediaService).delete("m2");
    }

    @Test
    void onUserDeleted_noFiles_noServiceCalls() {
        when(mediaRepository.findByUploaderId(99)).thenReturn(List.of());

        listener.onUserDeleted("99");

        verify(mediaService, never()).delete(any());
    }

    @Test
    void onUserDeleted_invalidUserId_logsErrorAndDoesNotThrow() {
        listener.onUserDeleted("not-a-number");

        verify(mediaRepository, never()).findByUploaderId(anyInt());
        verify(mediaService, never()).delete(any());
    }

    @Test
    void onUserDeleted_serviceThrows_propagates() {
        MediaFile f = MediaFile.builder().mediaId("m1").build();
        when(mediaRepository.findByUploaderId(5)).thenReturn(List.of(f));
        doThrow(new RuntimeException("S3 failure")).when(mediaService).delete("m1");

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> listener.onUserDeleted("5"));
    }
}
