package com.connecthub.message.resource;

import com.connecthub.message.dto.SendMessageRequest;
import com.connecthub.message.entity.Message;
import com.connecthub.message.entity.MessageReaction;
import com.connecthub.message.service.MessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageResourceTest {

    @Mock
    private MessageService svc;

    @InjectMocks
    private MessageResource res;

    @Test
    void send() {
        Message msg = new Message();
        SendMessageRequest request = new SendMessageRequest("m1", "r1", 1, "hello", "TEXT", null, null, null);
        when(svc.send(any(Message.class), eq("PRO"))).thenReturn(msg);
        assertEquals(HttpStatus.CREATED, res.send(request, "PRO").getStatusCode());
    }

    @Test
    void get() {
        LocalDateTime now = LocalDateTime.now();
        when(svc.getMessages("r1", now, 50)).thenReturn(List.of(new Message()));
        assertEquals(HttpStatus.OK, res.get("r1", now, 50).getStatusCode());
    }

    @Test
    void edit() {
        when(svc.edit("m1", "text", 1)).thenReturn(new Message());
        assertEquals(HttpStatus.OK, res.edit("m1", 1, Map.of("content", "text")).getStatusCode());
    }

    @Test
    void delete() {
        assertEquals(HttpStatus.NO_CONTENT, res.delete("m1", 1).getStatusCode());
        verify(svc).delete("m1", 1);
    }

    @Test
    void search() {
        when(svc.search("r1", "k")).thenReturn(List.of(new Message()));
        assertEquals(HttpStatus.OK, res.search("r1", "k").getStatusCode());
    }

    @Test
    void status() {
        assertEquals(HttpStatus.NO_CONTENT, res.status("m1", Map.of("status", "READ")).getStatusCode());
        verify(svc).updateStatus("m1", "READ");
    }

    @Test
    void unread() {
        LocalDateTime now = LocalDateTime.now();
        when(svc.unreadCount("r1", now)).thenReturn(5L);
        assertEquals(HttpStatus.OK, res.unread("r1", now).getStatusCode());
    }

    @Test
    void clear() {
        assertEquals(HttpStatus.NO_CONTENT, res.clear("r1").getStatusCode());
        verify(svc).clearHistory("r1");
    }

    @Test
    void react() {
        when(svc.addReaction("m1", 1, "👍")).thenReturn(new MessageReaction());
        assertEquals(HttpStatus.CREATED, res.react("m1", 1, Map.of("emoji", "👍")).getStatusCode());
    }

    @Test
    void unreact() {
        assertEquals(HttpStatus.NO_CONTENT, res.unreact("m1", 1, "👍").getStatusCode());
        verify(svc).removeReaction("m1", 1, "👍");
    }

    @Test
    void reactions() {
        when(svc.getReactions("m1")).thenReturn(List.of(new MessageReaction()));
        assertEquals(HttpStatus.OK, res.reactions("m1").getStatusCode());
    }
}
