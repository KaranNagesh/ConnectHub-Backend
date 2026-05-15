package com.connecthub.presence.resource;

import com.connecthub.presence.dto.UserPresence;
import com.connecthub.presence.service.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PresenceResourceTest {

    @Mock
    private PresenceService service;

    private PresenceResource resource;

    @BeforeEach
    void setUp() {
        resource = new PresenceResource(service);
    }

    @Test
    void online_callsServiceAndReturnsOk() {
        ResponseEntity<Void> response = resource.online(1, Map.of("deviceType", "WEB", "sessionId", "s1"));

        verify(service).setOnline(1, "WEB", "s1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void offline_callsServiceAndReturnsOk() {
        ResponseEntity<Void> response = resource.offline(2);

        verify(service).setOffline(2);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void status_callsServiceAndReturnsNoContent() {
        ResponseEntity<Void> response = resource.status(3, Map.of("status", "AWAY", "customMessage", "brb"));

        verify(service).updateStatus(3, "AWAY", "brb");
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void get_whenPresent_returnsOkWithBody() {
        UserPresence presence = UserPresence.builder().userId(4).status("ONLINE").build();
        when(service.get(4)).thenReturn(Optional.of(presence));

        ResponseEntity<UserPresence> response = resource.get(4);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(4, response.getBody().getUserId());
    }

    @Test
    void get_whenMissing_returnsNotFound() {
        when(service.get(99)).thenReturn(Optional.empty());

        ResponseEntity<UserPresence> response = resource.get(99);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void bulk_returnsServicePayload() {
        List<UserPresence> presences = List.of(UserPresence.builder().userId(1).status("ONLINE").build());
        when(service.getBulk(List.of(1, 2))).thenReturn(presences);

        ResponseEntity<List<UserPresence>> response = resource.bulk(List.of(1, 2));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void count_onlineUsers_check_and_ping_delegateToService() {
        when(service.onlineCount()).thenReturn(7);
        when(service.getOnlineUserIds()).thenReturn(List.of(1, 2));
        when(service.isOnline(7)).thenReturn(true);

        ResponseEntity<Integer> countResponse = resource.count();
        ResponseEntity<List<Integer>> usersResponse = resource.onlineUsers();
        ResponseEntity<Boolean> checkResponse = resource.check(7);
        ResponseEntity<Void> pingResponse = resource.ping(7);

        assertEquals(7, countResponse.getBody());
        assertEquals(List.of(1, 2), usersResponse.getBody());
        assertEquals(Boolean.TRUE, checkResponse.getBody());
        assertEquals(HttpStatus.OK, pingResponse.getStatusCode());
        verify(service).ping(7);
    }
}
