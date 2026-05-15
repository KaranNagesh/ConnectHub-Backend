package com.connecthub.payment.listener;

import com.connecthub.payment.entity.Subscription;
import com.connecthub.payment.repository.PaymentRepository;
import com.connecthub.payment.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserDeletionListenerTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private UserDeletionListener listener;

    @Test
    void onUserDeleted_validUserWithSubscription_deletesBoth() {
        Subscription sub = new Subscription();
        sub.setId(1L);
        when(subscriptionRepository.findByUserId(10)).thenReturn(Optional.of(sub));

        listener.onUserDeleted("10");

        verify(paymentRepository).deleteBySubscriptionId(1L);
        verify(subscriptionRepository).deleteByUserId(10);
    }

    @Test
    void onUserDeleted_validUserWithoutSubscription_skipsDeletion() {
        when(subscriptionRepository.findByUserId(11)).thenReturn(Optional.empty());

        listener.onUserDeleted("11");

        verify(paymentRepository, never()).deleteBySubscriptionId(any());
        verify(subscriptionRepository, never()).deleteByUserId(anyInt());
    }

    @Test
    void onUserDeleted_invalidUserId_handledGracefully() {
        listener.onUserDeleted("invalid");
        verify(subscriptionRepository, never()).findByUserId(anyInt());
    }

    @Test
    void onUserDeleted_databaseError_throwsException() {
        when(subscriptionRepository.findByUserId(12)).thenThrow(new RuntimeException("DB down"));
        assertThrows(RuntimeException.class, () -> listener.onUserDeleted("12"));
    }
}
