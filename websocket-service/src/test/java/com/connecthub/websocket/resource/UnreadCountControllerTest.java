package com.connecthub.websocket.resource;

import com.connecthub.websocket.service.UnreadCountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnreadCountControllerTest {

    @Mock
    private UnreadCountService unreadCountService;

    @InjectMocks
    private UnreadCountController controller;

    @Test
    void getUnreadCounts_allowsUserToReadOwnCounts() {
        Map<String, Long> counts = Map.of("room-1", 3L);
        when(unreadCountService.getAllForUser(5)).thenReturn(counts);

        ResponseEntity<Map<String, Long>> response = controller.getUnreadCounts(5, 5, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(counts);
    }

    @Test
    void getUnreadCounts_allowsPlatformAdmin() {
        Map<String, Long> counts = Map.of("room-1", 3L);
        when(unreadCountService.getAllForUser(5)).thenReturn(counts);

        ResponseEntity<Map<String, Long>> response = controller.getUnreadCounts(5, 99, "PLATFORM_ADMIN");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(counts);
    }

    @Test
    void getUnreadCounts_rejectsOtherUsers() {
        ResponseEntity<Map<String, Long>> response = controller.getUnreadCounts(5, 99, "USER");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(unreadCountService);
    }
}
