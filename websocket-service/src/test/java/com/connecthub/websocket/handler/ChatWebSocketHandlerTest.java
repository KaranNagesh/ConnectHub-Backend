package com.connecthub.websocket.handler;

import com.connecthub.websocket.config.RedisConfig;
import com.connecthub.websocket.dto.*;
import com.connecthub.websocket.interceptor.StompPrincipal;
import com.connecthub.websocket.service.MessagePersistenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatWebSocketHandlerTest {

    @Mock private SimpMessagingTemplate messaging;
    @Mock private StringRedisTemplate redis;
    @Mock private ObjectMapper mapper;
    @Mock private MessagePersistenceService persistenceService;
    @Mock private com.connecthub.websocket.client.MessageServiceClient messageServiceClient;
    @Mock private com.connecthub.websocket.service.TypingService typingService;
    @Mock private com.connecthub.websocket.service.DeliveryService deliveryService;
    @Mock private com.connecthub.websocket.service.UnreadCountService unreadCountService;
    @Mock private com.connecthub.websocket.client.RoomServiceClient roomServiceClient;
    @InjectMocks private ChatWebSocketHandler handler;

    private SimpMessageHeaderAccessor headers;
    private final StompPrincipal principal = new StompPrincipal("1", "alice");

    @BeforeEach
    void setUp() {
        headers = SimpMessageHeaderAccessor.create();
        headers.setUser(principal);
        lenient().when(roomServiceClient.isMember(anyString(), anyString(), anyString())).thenReturn(true);
    }

    // ── handleChat ──────────────────────────────────────────────────────────

    @Test
    void handleChat_generatesMessageIdWhenAbsent() throws Exception {
        ChatMessagePayload p = new ChatMessagePayload();
        p.setRoomId("r1"); p.setContent("Hello"); p.setType("TEXT");
        when(mapper.writeValueAsString(any(ChatMessagePayload.class))).thenReturn("{}");

        handler.handleChat(p, headers);

        assertNotNull(p.getMessageId(), "messageId must be set before broadcast");
        assertEquals(36, p.getMessageId().length(), "UUID has 36 chars");
    }

    @Test
    void handleChat_preservesExistingMessageId() throws Exception {
        ChatMessagePayload p = new ChatMessagePayload();
        p.setRoomId("r1"); p.setContent("Hi"); p.setType("TEXT");
        p.setMessageId("client-generated-id");
        when(mapper.writeValueAsString(any())).thenReturn("{}");

        handler.handleChat(p, headers);

        assertEquals("client-generated-id", p.getMessageId());
    }

    @Test
    void handleChat_setSenderIdAndUsernameFromPrincipal() throws Exception {
        ChatMessagePayload p = new ChatMessagePayload();
        p.setRoomId("r1"); p.setContent("Test"); p.setType("TEXT");
        when(mapper.writeValueAsString(any())).thenReturn("{}");

        handler.handleChat(p, headers);

        assertEquals(1, p.getSenderId());
        assertEquals("alice", p.getSenderUsername());
    }

    @Test
    void handleChat_setsTimestampAndDeliveryStatus() throws Exception {
        ChatMessagePayload p = new ChatMessagePayload();
        p.setRoomId("r1"); p.setContent("Hi"); p.setType("TEXT");
        when(mapper.writeValueAsString(any())).thenReturn("{}");

        long before = System.currentTimeMillis();
        handler.handleChat(p, headers);

        assertTrue(p.getTimestamp() >= before);
        assertEquals("SENT", p.getDeliveryStatus());
    }

    @Test
    void handleChat_sanitizesXSS() throws Exception {
        ChatMessagePayload p = new ChatMessagePayload();
        p.setRoomId("r1"); p.setContent("<script>alert('xss')</script>"); p.setType("TEXT");
        when(mapper.writeValueAsString(any())).thenReturn("{}");

        handler.handleChat(p, headers);

        assertFalse(p.getContent().contains("<script>"));
        assertTrue(p.getContent().contains("&lt;script&gt;"));
    }

    @Test
    void handleChat_broadcastsViaChatRedisChannel() throws Exception {
        ChatMessagePayload p = new ChatMessagePayload();
        p.setRoomId("r1"); p.setContent("Hello"); p.setType("TEXT");
        when(mapper.writeValueAsString(any())).thenReturn("{\"msg\":1}");

        handler.handleChat(p, headers);

        verify(redis).convertAndSend(eq(RedisConfig.CHAT_CHANNEL), eq("{\"msg\":1}"));
    }

    @Test
    void handleChat_broadcastsViaRedisPubSub() throws Exception {
        ChatMessagePayload p = new ChatMessagePayload();
        p.setRoomId("r1"); p.setContent("Hello"); p.setType("TEXT");
        when(mapper.writeValueAsString(any())).thenReturn("{\"test\":true}");

        handler.handleChat(p, headers);

        verify(redis).convertAndSend(eq(RedisConfig.CHAT_CHANNEL), eq("{\"test\":true}"));
    }

    @Test
    void handleChat_skipsKafkaFallbackWhenHttpPersistenceSucceeds() throws Exception {
        ChatMessagePayload p = new ChatMessagePayload();
        p.setRoomId("r1"); p.setContent("Hello"); p.setType("TEXT");
        when(mapper.writeValueAsString(any())).thenReturn("{}");

        handler.handleChat(p, headers);

        verify(persistenceService, never()).persistMessage(any(ChatMessagePayload.class));
    }

    @Test
    void handleChat_usesKafkaFallbackWhenHttpPersistenceFails() throws Exception {
        ChatMessagePayload p = new ChatMessagePayload();
        p.setRoomId("r1"); p.setContent("Hello"); p.setType("TEXT");
        when(messageServiceClient.persistMessage(anyMap(), anyString(), anyString()))
                .thenThrow(new RuntimeException("message-service down"));
        when(persistenceService.persistMessage(any(ChatMessagePayload.class))).thenReturn(true);
        when(mapper.writeValueAsString(any())).thenReturn("{}");

        handler.handleChat(p, headers);

        verify(persistenceService).persistMessage(any(ChatMessagePayload.class));
        verify(redis).convertAndSend(eq(RedisConfig.CHAT_CHANNEL), eq("{}"));
    }

    @Test
    void handleChat_nullContentDoesNotThrow() throws Exception {
        ChatMessagePayload p = new ChatMessagePayload();
        p.setRoomId("r1"); p.setType("IMAGE"); // no content
        when(mapper.writeValueAsString(any())).thenReturn("{}");

        assertDoesNotThrow(() -> handler.handleChat(p, headers));
        assertNull(p.getContent());
    }

    @Test
    void handleChat_mapperErrorHandledGracefully() throws Exception {
        ChatMessagePayload p = new ChatMessagePayload();
        p.setRoomId("r1"); p.setContent("Hello"); p.setType("TEXT");
        when(mapper.writeValueAsString(any())).thenThrow(new RuntimeException("serialization error"));

        assertDoesNotThrow(() -> handler.handleChat(p, headers));
        verify(redis, never()).convertAndSend(anyString(), anyString());
    }

    // ── handleTyping ─────────────────────────────────────────────────────────

    @Test
    void handleTyping_setsSenderIdAndBroadcastsDirectly() {
        TypingIndicatorPayload p = new TypingIndicatorPayload();
        p.setRoomId("r1"); p.setTyping(true);

        handler.handleTyping(p, headers);

        assertEquals(1, p.getSenderId());
        verify(messaging).convertAndSend(eq("/topic/room/r1/typing"), any(TypingIndicatorPayload.class));
        verify(redis, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    void handleTyping_stopTyping_broadcastsFalse() {
        TypingIndicatorPayload p = new TypingIndicatorPayload();
        p.setRoomId("r2"); p.setTyping(false);

        handler.handleTyping(p, headers);

        ArgumentCaptor<TypingIndicatorPayload> cap = ArgumentCaptor.forClass(TypingIndicatorPayload.class);
        verify(messaging).convertAndSend(eq("/topic/room/r2/typing"), cap.capture());
        assertFalse(cap.getValue().isTyping());
    }

    // ── handleRead ───────────────────────────────────────────────────────────

    @Test
    void handleRead_setsReaderIdAndBroadcasts() {
        ReadReceiptPayload p = new ReadReceiptPayload();
        p.setRoomId("r1"); p.setUpToMessageId("m5");

        handler.handleRead(p, headers);

        assertEquals(1, p.getReaderId());
        verify(messaging).convertAndSend(eq("/topic/room/r1/read"), any(ReadReceiptPayload.class));
    }

    // ── handleReaction ───────────────────────────────────────────────────────

    @Test
    void handleReaction_routesThroughReactionRedisChannel() throws Exception {
        ReactionPayload p = new ReactionPayload();
        p.setRoomId("r1"); p.setMessageId("m1"); p.setEmoji("👍"); p.setAction("ADD");
        when(mapper.writeValueAsString(any(ReactionPayload.class))).thenReturn("{\"reaction\":1}");

        handler.handleReaction(p, headers);

        assertEquals(1, p.getSenderId());
        verify(redis).convertAndSend(eq(RedisConfig.REACTION_CHANNEL), eq("{\"reaction\":1}"));
        verify(messaging, never()).convertAndSend(anyString(), (Object) any());
    }

    @Test
    void handleReaction_mapperErrorHandledGracefully() throws Exception {
        ReactionPayload p = new ReactionPayload();
        p.setRoomId("r1"); p.setMessageId("m1"); p.setEmoji("👍");
        when(mapper.writeValueAsString(any())).thenThrow(new RuntimeException("err"));

        assertDoesNotThrow(() -> handler.handleReaction(p, headers));
    }

    // ── handleEdit ───────────────────────────────────────────────────────────

    @Test
    void handleEdit_routesThroughEditRedisChannel() throws Exception {
        MessageEditPayload p = new MessageEditPayload();
        p.setRoomId("r1"); p.setMessageId("m1"); p.setNewContent("updated text");
        when(mapper.writeValueAsString(any(MessageEditPayload.class))).thenReturn("{\"edit\":1}");

        handler.handleEdit(p, headers);

        assertEquals(1, p.getEditorId());
        verify(redis).convertAndSend(eq(RedisConfig.EDIT_CHANNEL), eq("{\"edit\":1}"));
        verify(messaging, never()).convertAndSend(anyString(), (Object) any());
    }

    @Test
    void handleEdit_sanitizesXSSInNewContent() throws Exception {
        MessageEditPayload p = new MessageEditPayload();
        p.setRoomId("r1"); p.setMessageId("m1"); p.setNewContent("<b>bold</b>");
        when(mapper.writeValueAsString(any())).thenReturn("{}");

        handler.handleEdit(p, headers);

        assertFalse(p.getNewContent().contains("<b>"));
        assertTrue(p.getNewContent().contains("&lt;b&gt;"));
    }

    @Test
    void handleEdit_nullNewContentDoesNotThrow() throws Exception {
        MessageEditPayload p = new MessageEditPayload();
        p.setRoomId("r1"); p.setMessageId("m1"); // newContent = null
        when(mapper.writeValueAsString(any())).thenReturn("{}");

        assertDoesNotThrow(() -> handler.handleEdit(p, headers));
        assertNull(p.getNewContent());
    }

    // ── handleDelete ─────────────────────────────────────────────────────────

    @Test
    void handleDelete_routesThroughDeleteRedisChannel() throws Exception {
        MessageDeletePayload p = new MessageDeletePayload();
        p.setRoomId("r1"); p.setMessageId("m1");
        when(mapper.writeValueAsString(any(MessageDeletePayload.class))).thenReturn("{\"del\":1}");

        handler.handleDelete(p, headers);

        assertEquals(1, p.getDeleterId());
        verify(redis).convertAndSend(eq(RedisConfig.DELETE_CHANNEL), eq("{\"del\":1}"));
        verify(messaging, never()).convertAndSend(anyString(), (Object) any());
    }

    @Test
    void handleDelete_mapperErrorHandledGracefully() throws Exception {
        MessageDeletePayload p = new MessageDeletePayload();
        p.setRoomId("r1"); p.setMessageId("m1");
        when(mapper.writeValueAsString(any())).thenThrow(new RuntimeException("err"));

        assertDoesNotThrow(() -> handler.handleDelete(p, headers));
    }
}
