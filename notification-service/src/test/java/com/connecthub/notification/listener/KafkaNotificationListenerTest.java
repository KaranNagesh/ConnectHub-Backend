package com.connecthub.notification.listener;

import com.connecthub.notification.entity.Notification;
import com.connecthub.notification.client.AuthServiceClient;
import com.connecthub.notification.service.EmailSender;
import com.connecthub.notification.service.FcmSender;
import com.connecthub.notification.service.NotifService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaNotificationListenerTest {

    @Mock NotifService notifService;
    @Mock ObjectMapper objectMapper;
    @Mock AuthServiceClient authServiceClient;
    @Mock EmailSender emailSender;
    @Mock FcmSender fcmSender;

    @InjectMocks KafkaNotificationListener listener;

    @Test
    void processOfflineNotification_savesNotification() throws Exception {
        String json = "{\"recipientId\":1,\"actorId\":2,\"type\":\"NEW_MESSAGE\"," +
                "\"title\":\"New msg\",\"message\":\"Hello\",\"roomId\":\"r1\",\"messageId\":\"m1\"}";

        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("recipientId", 1);
        payload.put("actorId", 2);
        payload.put("type", "NEW_MESSAGE");
        payload.put("title", "New msg");
        payload.put("message", "Hello");
        payload.put("roomId", "r1");
        payload.put("messageId", "m1");

        when(objectMapper.readValue(eq(json), eq(java.util.Map.class))).thenReturn(payload);
        when(objectMapper.convertValue(any(), eq(java.util.Map.class))).thenReturn(payload);

        listener.processOfflineNotification(json, "notifications.offline", 0, 0L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifService).send(captor.capture());
        verify(fcmSender).sendToUser(eq(1), eq("New msg"), eq("Hello"), anyMap());
        verifyNoInteractions(authServiceClient, emailSender);
        Notification saved = captor.getValue();
        assertThat(saved.getRecipientId()).isEqualTo(1);
        assertThat(saved.getType()).isEqualTo("NEW_MESSAGE");
        assertThat(saved.isRead()).isFalse();
    }

    @Test
    void handleDlq_logsWithoutThrowing() {
        // DLQ handler should log and not throw
        listener.handleDlq("{}", "notifications.offline.dlq", 0L);
        // No exception = PASS
    }
}
