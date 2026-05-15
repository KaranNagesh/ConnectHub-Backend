package com.connecthub.notification.service;

import com.connecthub.notification.entity.Notification;
import com.connecthub.notification.repository.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotifServiceTest {

    @Mock private NotificationRepository repo;
    @Mock private StringRedisTemplate redis;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private NotifService svc;

    @Test
    void send_persistsAndPublishes() throws Exception {
        Notification n = new Notification();
        when(repo.save(any())).thenReturn(n);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        svc.send(n);

        verify(repo).save(n);
        verify(redis).convertAndSend("chat:notifications", "{}");
    }

    @Test
    void send_returnsExistingWhenMessageNotificationAlreadyExists() {
        Notification n = new Notification();
        n.setRecipientId(1);
        n.setType("NEW_MESSAGE");
        n.setMessageId("m1");
        Notification existing = new Notification();
        existing.setNotificationId(99);
        when(repo.findFirstByRecipientIdAndTypeAndMessageIdOrderByCreatedAtDesc(1, "NEW_MESSAGE", "m1"))
                .thenReturn(Optional.of(existing));

        Notification result = svc.send(n);

        assertEquals(existing, result);
        verify(repo, never()).save(any());
        verifyNoInteractions(redis, objectMapper);
    }

    @Test
    void markRead() {
        Notification n = new Notification();
        when(repo.findById(1)).thenReturn(Optional.of(n));

        svc.markRead(1);

        verify(repo).save(n);
    }

    @Test
    void markAllRead() {
        svc.markAllRead(1);
        verify(repo).markAllRead(1);
    }

    @Test
    void getByRecipient() {
        when(repo.findByRecipientIdOrderByCreatedAtDesc(1)).thenReturn(List.of(new Notification()));
        assertEquals(1, svc.getByRecipient(1).size());
    }

    @Test
    void unreadCount() {
        when(repo.countByRecipientIdAndIsRead(1, false)).thenReturn(5);
        assertEquals(5, svc.unreadCount(1));
    }

    @Test
    void delete() {
        svc.delete(1);
        verify(repo).deleteById(1);
    }
}
