package com.connecthub.websocket.event;

import com.connecthub.websocket.config.RedisConfig;
import com.connecthub.websocket.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisMessageSubscriberTest {

    @Mock private SimpMessagingTemplate messaging;
    @Mock private ObjectMapper objectMapper;
    @InjectMocks private RedisMessageSubscriber subscriber;

    private org.springframework.data.redis.connection.Message msg(String channel, String body) {
        return new DefaultMessage(channel.getBytes(), body.getBytes());
    }

    // ── CHAT channel ─────────────────────────────────────────────────────────

    @Test
    void chatChannel_forwardsToRoomTopic() throws Exception {
        ChatMessagePayload p = new ChatMessagePayload();
        p.setRoomId("r1"); p.setContent("Hi");
        when(objectMapper.readValue(any(byte[].class), eq(ChatMessagePayload.class))).thenReturn(p);

        subscriber.onMessage(msg(RedisConfig.CHAT_CHANNEL, "{}"), null);

        verify(messaging).convertAndSend(eq("/topic/room/r1"), any(ChatMessagePayload.class));
    }

    // ── PRESENCE channel ─────────────────────────────────────────────────────

    @Test
    void presenceChannel_forwardsToPresenceTopic() throws Exception {
        PresenceUpdatePayload p = new PresenceUpdatePayload();
        p.setUserId(5); p.setStatus("ONLINE");
        when(objectMapper.readValue(any(byte[].class), eq(PresenceUpdatePayload.class))).thenReturn(p);

        subscriber.onMessage(msg(RedisConfig.PRESENCE_CHANNEL, "{}"), null);

        verify(messaging).convertAndSend(eq("/topic/presence"), any(PresenceUpdatePayload.class));
    }

    // ── EDIT channel ─────────────────────────────────────────────────────────

    @Test
    void editChannel_forwardsToRoomEditTopic() throws Exception {
        MessageEditPayload p = new MessageEditPayload();
        p.setRoomId("r2"); p.setMessageId("m1"); p.setNewContent("updated");
        when(objectMapper.readValue(any(byte[].class), eq(MessageEditPayload.class))).thenReturn(p);

        subscriber.onMessage(msg(RedisConfig.EDIT_CHANNEL, "{}"), null);

        verify(messaging).convertAndSend(eq("/topic/room/r2/edit"), any(MessageEditPayload.class));
    }

    // ── DELETE channel ───────────────────────────────────────────────────────

    @Test
    void deleteChannel_forwardsToRoomDeleteTopic() throws Exception {
        MessageDeletePayload p = new MessageDeletePayload();
        p.setRoomId("r3"); p.setMessageId("m2");
        when(objectMapper.readValue(any(byte[].class), eq(MessageDeletePayload.class))).thenReturn(p);

        subscriber.onMessage(msg(RedisConfig.DELETE_CHANNEL, "{}"), null);

        verify(messaging).convertAndSend(eq("/topic/room/r3/delete"), any(MessageDeletePayload.class));
    }

    // ── REACTION channel ─────────────────────────────────────────────────────

    @Test
    void reactionChannel_forwardsToRoomReactionsTopic() throws Exception {
        ReactionPayload p = new ReactionPayload();
        p.setRoomId("r4"); p.setMessageId("m3"); p.setEmoji("👍");
        when(objectMapper.readValue(any(byte[].class), eq(ReactionPayload.class))).thenReturn(p);

        subscriber.onMessage(msg(RedisConfig.REACTION_CHANNEL, "{}"), null);

        verify(messaging).convertAndSend(eq("/topic/room/r4/reactions"), any(ReactionPayload.class));
    }

    // ── Unknown channel ──────────────────────────────────────────────────────

    @Test
    void unknownChannel_noMessageSent() throws Exception {
        subscriber.onMessage(msg("chat:unknown", "{}"), null);
        verifyNoInteractions(messaging);
    }

    // ── Deserialisation error ────────────────────────────────────────────────

    @Test
    void chatChannel_deserializationError_doesNotThrow() throws Exception {
        when(objectMapper.readValue(any(byte[].class), eq(ChatMessagePayload.class)))
            .thenThrow(new RuntimeException("bad json"));

        assertDoesNotThrow(() -> subscriber.onMessage(msg(RedisConfig.CHAT_CHANNEL, "bad"), null));
        verifyNoInteractions(messaging);
    }
}
