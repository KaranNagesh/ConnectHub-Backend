package com.connecthub.room.listener;

import com.connecthub.room.repository.RoomMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserDeletionListenerTest {

    @Mock
    private RoomMemberRepository roomMemberRepository;

    @InjectMocks
    private UserDeletionListener listener;

    @Test
    void onUserDeleted_deletesMembershipsForValidUserId() {
        listener.onUserDeleted("42");

        verify(roomMemberRepository).deleteByUserId(42);
    }

    @Test
    void onUserDeleted_ignoresInvalidUserId() {
        listener.onUserDeleted("not-a-number");

        verifyNoInteractions(roomMemberRepository);
    }

    @Test
    void onUserDeleted_rethrowsRepositoryFailureForKafkaRetry() {
        doThrow(new IllegalStateException("db down")).when(roomMemberRepository).deleteByUserId(42);

        assertThrows(IllegalStateException.class, () -> listener.onUserDeleted("42"));
    }
}
