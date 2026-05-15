package com.connecthub.notification.resource;

import com.connecthub.notification.entity.DeviceToken;
import com.connecthub.notification.repository.DeviceTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceTokenResourceTest {

    @Mock
    private DeviceTokenRepository repo;

    @InjectMocks
    private DeviceTokenResource resource;

    @Test
    void register_rejectsMissingToken() {
        ResponseEntity<DeviceToken> response = resource.register(5, Map.of("platform", "WEB"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(repo);
    }

    @Test
    void register_savesNewTrimmedTokenWithDefaultPlatform() {
        when(repo.findByToken("abc")).thenReturn(Optional.empty());
        when(repo.save(org.mockito.ArgumentMatchers.any(DeviceToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<DeviceToken> response = resource.register(5, Map.of("token", " abc "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<DeviceToken> captor = ArgumentCaptor.forClass(DeviceToken.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(5);
        assertThat(captor.getValue().getToken()).isEqualTo("abc");
        assertThat(captor.getValue().getPlatform()).isEqualTo("WEB");
        assertThat(captor.getValue().getLastSeenAt()).isNotNull();
    }

    @Test
    void register_updatesExistingTokenPlatform() {
        DeviceToken existing = DeviceToken.builder().deviceTokenId(11).token("abc").build();
        when(repo.findByToken("abc")).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);

        ResponseEntity<DeviceToken> response = resource.register(6, Map.of("token", "abc", "platform", "ANDROID"));

        assertThat(response.getBody()).isSameAs(existing);
        assertThat(existing.getUserId()).isEqualTo(6);
        assertThat(existing.getPlatform()).isEqualTo("ANDROID");
    }

    @Test
    void unregister_deletesWhenTokenIsPresent() {
        ResponseEntity<Void> response = resource.unregister(5, Map.of("token", " abc "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(repo).deleteByUserIdAndToken(5, "abc");
    }

    @Test
    void unregister_ignoresBlankToken() {
        ResponseEntity<Void> response = resource.unregister(5, Map.of("token", " "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verifyNoInteractions(repo);
    }
}
