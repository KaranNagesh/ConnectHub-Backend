package com.connecthub.message.service;

import com.connecthub.message.entity.Message;
import com.connecthub.message.entity.MessageReaction;
import com.connecthub.message.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock MessageRepository msgRepo;
    @Mock ReactionRepository reactRepo;
    @Mock MessageTierRateLimiter messageTierRateLimiter;
    @InjectMocks MessageService svc;

    // ── send ─────────────────────────────────────────────────────────────────

    @Test
    void send_sanitizesXSS() {
        when(messageTierRateLimiter.tryAcquire(anyString(), anyString())).thenReturn(true);
        Message m = Message.builder().roomId("r1").senderId(1)
            .content("<script>alert(1)</script>").type("TEXT").build();
        when(msgRepo.save(any())).thenAnswer(i -> { Message s = i.getArgument(0); s.setMessageId("m1"); return s; });

        Message result = svc.send(m, "FREE");

        assertFalse(result.getContent().contains("<script>"));
        assertTrue(result.getContent().contains("&lt;script&gt;"));
    }

    @Test
    void send_setsDeliveryStatusToSent() {
        when(messageTierRateLimiter.tryAcquire(anyString(), anyString())).thenReturn(true);
        Message m = Message.builder().roomId("r1").senderId(1).content("hi").type("TEXT").build();
        when(msgRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Message result = svc.send(m, "FREE");
        assertEquals("SENT", result.getDeliveryStatus());
    }

    @Test
    void send_nullContentIsAllowed() {
        when(messageTierRateLimiter.tryAcquire(anyString(), anyString())).thenReturn(true);
        Message m = Message.builder().roomId("r1").senderId(1).type("IMAGE").build();
        when(msgRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        assertDoesNotThrow(() -> svc.send(m, "FREE"));
        assertNull(m.getContent());
    }

    // ── getMessages ──────────────────────────────────────────────────────────

    @Test
    void getMessages_withoutCursor_callsFirstPageQuery() {
        List<Message> msgs = List.of(Message.builder().messageId("m1").build());
        when(msgRepo.findByRoomIdAndIsDeletedFalseOrderBySentAtDesc(eq("r1"), any(Pageable.class)))
            .thenReturn(msgs);

        List<Message> result = svc.getMessages("r1", null, 50);
        assertEquals(1, result.size());
    }

    @Test
    void getMessages_withCursor_callsCursorQuery() {
        LocalDateTime cursor = LocalDateTime.now().minusHours(1);
        List<Message> msgs = List.of(Message.builder().messageId("m2").build());
        when(msgRepo.findByRoomIdAndIsDeletedFalseAndSentAtBeforeOrderBySentAtDesc(eq("r1"), eq(cursor), any(Pageable.class)))
            .thenReturn(msgs);

        List<Message> result = svc.getMessages("r1", cursor, 50);
        assertEquals(1, result.size());
    }

    // ── edit ─────────────────────────────────────────────────────────────────

    @Test
    void edit_byOwner_success() {
        Message existing = Message.builder().messageId("m1").senderId(1).content("old").build();
        when(msgRepo.findById("m1")).thenReturn(Optional.of(existing));
        when(msgRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Message edited = svc.edit("m1", "new content", 1);

        assertEquals("new content", edited.getContent());
        assertTrue(edited.isEdited());
        assertNotNull(edited.getEditedAt());
    }

    @Test
    void edit_xssSanitizedInNewContent() {
        Message existing = Message.builder().messageId("m1").senderId(1).content("old").build();
        when(msgRepo.findById("m1")).thenReturn(Optional.of(existing));
        when(msgRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Message edited = svc.edit("m1", "<img onerror=alert(1)>", 1);
        assertFalse(edited.getContent().contains("<img"));
    }

    @Test
    void edit_byOtherUser_throws() {
        when(msgRepo.findById("m1")).thenReturn(Optional.of(
            Message.builder().messageId("m1").senderId(1).build()));
        assertThrows(RuntimeException.class, () -> svc.edit("m1", "hacked", 999));
    }

    @Test
    void edit_messageNotFound_throws() {
        when(msgRepo.findById("x")).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> svc.edit("x", "content", 1));
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    void delete_softDeletes() {
        Message m = Message.builder().messageId("m1").senderId(1).isDeleted(false).build();
        when(msgRepo.findById("m1")).thenReturn(Optional.of(m));
        when(msgRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.delete("m1", 1);
        assertTrue(m.isDeleted());
    }

    @Test
    void delete_messageNotFound_throws() {
        when(msgRepo.findById("x")).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class, () -> svc.delete("x", 1));
    }

    // ── search ───────────────────────────────────────────────────────────────

    @Test
    void search_returnsMatchingMessages() {
        List<Message> matches = List.of(
            Message.builder().messageId("m1").content("hello world").build()
        );
        when(msgRepo.searchInRoom("r1", "hello")).thenReturn(matches);

        List<Message> result = svc.search("r1", "hello");
        assertEquals(1, result.size());
        assertEquals("hello world", result.get(0).getContent());
    }

    // ── updateStatus ─────────────────────────────────────────────────────────

    @Test
    void updateStatus_existingMessage_updatesStatus() {
        Message m = Message.builder().messageId("m1").deliveryStatus("SENT").build();
        when(msgRepo.findById("m1")).thenReturn(Optional.of(m));
        when(msgRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.updateStatus("m1", "READ");
        assertEquals("READ", m.getDeliveryStatus());
    }

    @Test
    void updateStatus_missingMessage_noOp() {
        when(msgRepo.findById("x")).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> svc.updateStatus("x", "READ"));
        verify(msgRepo, never()).save(any());
    }

    // ── clearHistory ─────────────────────────────────────────────────────────

    @Test
    void clearHistory_deletesAllRoomMessages() {
        svc.clearHistory("r1");
        verify(msgRepo).deleteByRoomId("r1");
    }

    // ── reactions ────────────────────────────────────────────────────────────

    @Test
    void addReaction_newReaction_saved() {
        when(reactRepo.existsByMessageIdAndUserIdAndEmoji("m1", 1, "👍")).thenReturn(false);
        MessageReaction saved = MessageReaction.builder().messageId("m1").userId(1).emoji("👍").build();
        when(reactRepo.save(any())).thenReturn(saved);

        MessageReaction result = svc.addReaction("m1", 1, "👍");
        assertEquals("👍", result.getEmoji());
    }

    @Test
    void addReaction_duplicate_throws() {
        when(reactRepo.existsByMessageIdAndUserIdAndEmoji("m1", 1, "👍")).thenReturn(true);
        assertThrows(RuntimeException.class, () -> svc.addReaction("m1", 1, "👍"));
    }

    @Test
    void removeReaction_callsRepository() {
        svc.removeReaction("m1", 1, "👍");
        verify(reactRepo).deleteByMessageIdAndUserIdAndEmoji("m1", 1, "👍");
    }

    @Test
    void getReactions_returnsAll() {
        List<MessageReaction> reactions = List.of(
            MessageReaction.builder().messageId("m1").userId(1).emoji("❤️").build(),
            MessageReaction.builder().messageId("m1").userId(2).emoji("❤️").build()
        );
        when(reactRepo.findByMessageId("m1")).thenReturn(reactions);

        List<MessageReaction> result = svc.getReactions("m1");
        assertEquals(2, result.size());
    }

    // ── unreadCount ──────────────────────────────────────────────────────────

    @Test
    void unreadCount_withLastReadAt_usesDateQuery() {
        LocalDateTime lastRead = LocalDateTime.now().minusHours(2);
        when(msgRepo.countByRoomIdAndSentAtAfterAndIsDeletedFalse("r1", lastRead)).thenReturn(5L);
        assertEquals(5L, svc.unreadCount("r1", lastRead));
    }

    @Test
    void unreadCount_noLastReadAt_countsAll() {
        when(msgRepo.countByRoomIdAndIsDeletedFalse("r1")).thenReturn(42L);
        assertEquals(42L, svc.unreadCount("r1", null));
    }
}
