package com.connecthub.notification.resource;

import com.connecthub.notification.dto.SendNotificationRequest;
import com.connecthub.notification.entity.Notification;
import com.connecthub.notification.service.NotifService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotifResourceTest {

    @Mock
    private NotifService service;

    private NotifResource resource;

    @BeforeEach
    void setUp() {
        resource = new NotifResource(service);
    }

    @Test
    void send_returnsCreatedBody() {
        Notification input = new Notification();
        SendNotificationRequest request = new SendNotificationRequest(7, 2, "NEW_MESSAGE", "Title", "Body", "r1", "m1");
        when(service.send(any(Notification.class))).thenReturn(input);

        ResponseEntity<Notification> response = resource.send(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(input, response.getBody());
    }

    @Test
    void get_returnsRecipientNotifications() {
        List<Notification> notifications = List.of(new Notification());
        when(service.getByRecipient(7)).thenReturn(notifications);

        ResponseEntity<List<Notification>> response = resource.get(7);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void read_marksNotificationRead() {
        ResponseEntity<Void> response = resource.read(11);

        verify(service).markRead(11);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void readAll_marksAllRead() {
        ResponseEntity<Void> response = resource.readAll(3);

        verify(service).markAllRead(3);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void unread_returnsCount() {
        when(service.unreadCount(9)).thenReturn(4);

        ResponseEntity<Integer> response = resource.unread(9);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(4, response.getBody());
    }

    @Test
    void del_deletesNotification() {
        ResponseEntity<Void> response = resource.del(15);

        verify(service).delete(15);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }
}
