package com.connecthub.message.listener;

import com.connecthub.message.repository.MessageRepository;
import com.connecthub.message.repository.ReactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UserDeletionListenerTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ReactionRepository reactionRepository;

    @InjectMocks
    private UserDeletionListener userDeletionListener;

    @Test
    void testOnUserDeleted_Success() {
        String userIdStr = "123";
        userDeletionListener.onUserDeleted(userIdStr);
        verify(messageRepository, times(1)).deleteBySenderId(123);
        verify(reactionRepository, times(1)).deleteByUserId(123);
    }

    @Test
    void testOnUserDeleted_InvalidFormat() {
        String userIdStr = "invalid";
        userDeletionListener.onUserDeleted(userIdStr);
        verify(messageRepository, never()).deleteBySenderId(anyInt());
        verify(reactionRepository, never()).deleteByUserId(anyInt());
    }

    @Test
    void testOnUserDeleted_Exception() {
        String userIdStr = "123";
        doThrow(new RuntimeException("DB error")).when(messageRepository).deleteBySenderId(123);
        
        assertThrows(RuntimeException.class, () -> userDeletionListener.onUserDeleted(userIdStr));
        
        verify(messageRepository, times(1)).deleteBySenderId(123);
        verify(reactionRepository, never()).deleteByUserId(123);
    }
}
