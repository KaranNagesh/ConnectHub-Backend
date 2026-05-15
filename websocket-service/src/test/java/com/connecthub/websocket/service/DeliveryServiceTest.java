package com.connecthub.websocket.service;

import com.connecthub.websocket.client.NotificationServiceClient;
import com.connecthub.websocket.client.RoomServiceClient;
import com.connecthub.websocket.dto.ChatMessagePayload;
import com.connecthub.websocket.dto.RoomDto;
import com.connecthub.websocket.dto.RoomMemberDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    @Mock private SimpMessagingTemplate messaging;
    @Mock private StringRedisTemplate redis;
    @Mock private RoomServiceClient roomServiceClient;
    @Mock private NotificationServiceClient notificationServiceClient;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private UnreadCountService unreadCountService;
    @Mock private SetOperations<String, String> setOps;
    @Mock private ListOperations<String, String> listOps;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        deliveryService = new DeliveryService(
                messaging,
                redis,
                objectMapper,
                roomServiceClient,
                notificationServiceClient,
                kafkaTemplate,
                unreadCountService
        );
        lenient().when(redis.opsForSet()).thenReturn(setOps);
    }

    @Test
    void deliverToRoomMembers_onlineUserCreatesNotificationWithoutQueueingMessage() throws Exception {
        ChatMessagePayload msg = message();
        RoomMemberDto member = member(2);
        when(roomServiceClient.getRoomMembers("r1", "1")).thenReturn(List.of(member));
        when(roomServiceClient.getRoom("r1", "1")).thenReturn(room("DM"));
        when(setOps.isMember("presence:online", "2")).thenReturn(true);

        deliveryService.deliverToRoomMembers(msg);

        verify(unreadCountService).increment(2, "r1");
        verify(messaging).convertAndSendToUser("2", "/queue/messages", msg);
        verify(redis, never()).opsForList();

        Map<String, Object> notification = capturedNotification();
        assertEquals(2, notification.get("recipientId"));
        assertEquals("NEW_MESSAGE", notification.get("type"));
        assertEquals("DM", notification.get("roomType"));
        assertEquals("hello", notification.get("message"));
    }

    @Test
    void deliverToRoomMembers_offlineUserQueuesMessageAndCreatesNotification() throws Exception {
        ChatMessagePayload msg = message();
        RoomMemberDto member = member(2);
        when(roomServiceClient.getRoomMembers("r1", "1")).thenReturn(List.of(member));
        when(roomServiceClient.getRoom("r1", "1")).thenReturn(room("DM"));
        when(setOps.isMember("presence:online", "2")).thenReturn(false);
        when(redis.opsForList()).thenReturn(listOps);

        deliveryService.deliverToRoomMembers(msg);

        verify(listOps).rightPush(eq("pending:messages:2"), anyString());
        verify(redis).expire("pending:messages:2", 7, TimeUnit.DAYS);

        Map<String, Object> notification = capturedNotification();
        assertEquals(2, notification.get("recipientId"));
        assertEquals("NEW_MESSAGE", notification.get("type"));
        assertFalse(((String) notification.get("message")).isBlank());
    }

    @Test
    void deliverToRoomMembers_kafkaFailureFallsBackToNotificationService() {
        ChatMessagePayload msg = message();
        RoomMemberDto member = member(2);
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new KafkaException("broker unavailable"));

        when(roomServiceClient.getRoomMembers("r1", "1")).thenReturn(List.of(member));
        when(roomServiceClient.getRoom("r1", "1")).thenReturn(room("DM"));
        when(setOps.isMember("presence:online", "2")).thenReturn(true);
        when(kafkaTemplate.send(eq("notifications.offline"), anyString())).thenReturn(failed);

        deliveryService.deliverToRoomMembers(msg);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationServiceClient).createNotification(bodyCaptor.capture(), eq("2"));
        assertEquals(2, bodyCaptor.getValue().get("recipientId"));
        assertEquals("NEW_MESSAGE", bodyCaptor.getValue().get("type"));
    }

    @Test
    void deliverToRoomMembers_memberLookupFailureIsCaught() {
        ChatMessagePayload msg = message();
        when(roomServiceClient.getRoomMembers("r1", "1")).thenThrow(new IllegalStateException("room-service down"));

        deliveryService.deliverToRoomMembers(msg);

        verify(messaging, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void deliverToRoomMembers_usesFallbackRoomTypeSenderAndMediaPreview() throws Exception {
        ChatMessagePayload msg = message();
        msg.setSenderUsername(null);
        msg.setContent(null);
        when(roomServiceClient.getRoomMembers("r1", "1")).thenReturn(List.of(member(2)));
        when(roomServiceClient.getRoom("r1", "1")).thenThrow(new IllegalStateException("not found"));
        when(setOps.isMember("presence:online", "2")).thenReturn(true);
        when(kafkaTemplate.send(eq("notifications.offline"), anyString())).thenReturn(null);

        deliveryService.deliverToRoomMembers(msg);

        Map<String, Object> notification = capturedDirectNotification();
        assertEquals("GROUP", notification.get("roomType"));
        assertEquals("New message from someone", notification.get("title"));
        assertEquals("(media)", notification.get("message"));
    }

    @Test
    void deliverToRoomMembers_queueSerializationFailureDoesNotStopNotificationAttempt() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any(ChatMessagePayload.class)))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("nope") {});
        DeliveryService service = new DeliveryService(
                messaging,
                redis,
                failingMapper,
                roomServiceClient,
                notificationServiceClient,
                kafkaTemplate,
                unreadCountService
        );
        ChatMessagePayload msg = message();
        when(roomServiceClient.getRoomMembers("r1", "1")).thenReturn(List.of(member(2)));
        when(roomServiceClient.getRoom("r1", "1")).thenReturn(room("DM"));
        when(setOps.isMember("presence:online", "2")).thenReturn(false);

        service.deliverToRoomMembers(msg);

        verify(listOps, never()).rightPush(anyString(), anyString());
        verify(notificationServiceClient).createNotification(any(Map.class), eq("2"));
    }

    @Test
    void flushPendingMessages_returnsWhenQueueIsEmpty() {
        when(redis.opsForList()).thenReturn(listOps);
        when(listOps.size("pending:messages:2")).thenReturn(0L);

        deliveryService.flushPendingMessages("2");

        verify(listOps, never()).leftPop("pending:messages:2");
        verify(messaging, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void flushPendingMessages_deliversReadableMessagesAndSkipsBadJson() throws Exception {
        ChatMessagePayload msg = message();
        String json = objectMapper.writeValueAsString(msg);
        when(redis.opsForList()).thenReturn(listOps);
        when(listOps.size("pending:messages:2")).thenReturn(2L);
        when(listOps.leftPop("pending:messages:2")).thenReturn(json, "{bad-json", null);

        deliveryService.flushPendingMessages("2");

        verify(messaging).convertAndSendToUser(eq("2"), eq("/queue/messages"), any(ChatMessagePayload.class));
        verify(listOps, times(3)).leftPop("pending:messages:2");
    }

    @Test
    void updateRoomTimestamp_delegatesAndSwallowsFailures() {
        deliveryService.updateRoomTimestamp("r1", "1");
        verify(roomServiceClient).updateLastMessageAt("r1", "1");

        doThrow(new IllegalStateException("down")).when(roomServiceClient).updateLastMessageAt("r2", "1");
        deliveryService.updateRoomTimestamp("r2", "1");
    }

    @Test
    void persistLastRead_delegatesAndSwallowsFailures() {
        deliveryService.persistLastRead("r1", "1");
        verify(roomServiceClient).updateLastRead("r1", "1", "1");

        doThrow(new IllegalStateException("down")).when(roomServiceClient).updateLastRead("r2", "1", "1");
        deliveryService.persistLastRead("r2", "1");
    }

    @Test
    void flushPendingMessagesWithDelayRestoresInterruptFlag() {
        Thread.currentThread().interrupt();

        deliveryService.flushPendingMessagesWithDelay("2");

        assertTrue(Thread.interrupted());
    }

    @Test
    void pushNotification_sendsToUserNotificationQueue() {
        Map<String, Object> notification = Map.of("type", "PING");

        deliveryService.pushNotification(2, notification);

        verify(messaging).convertAndSendToUser("2", "/queue/notifications", notification);
    }

    private Map<String, Object> capturedNotification() throws Exception {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("notifications.offline"), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> notification = objectMapper.readValue((String) captor.getValue(), Map.class);
        return notification;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedDirectNotification() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationServiceClient).createNotification(captor.capture(), eq("2"));
        return captor.getValue();
    }

    private ChatMessagePayload message() {
        ChatMessagePayload msg = new ChatMessagePayload();
        msg.setMessageId("m1");
        msg.setRoomId("r1");
        msg.setSenderId(1);
        msg.setSenderUsername("Karan");
        msg.setContent("hello");
        return msg;
    }

    private RoomMemberDto member(Integer userId) {
        RoomMemberDto member = new RoomMemberDto();
        member.setUserId(userId);
        return member;
    }

    private RoomDto room(String type) {
        RoomDto room = new RoomDto();
        room.setRoomId("r1");
        room.setType(type);
        return room;
    }
}
