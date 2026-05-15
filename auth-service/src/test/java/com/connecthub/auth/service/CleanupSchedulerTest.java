package com.connecthub.auth.service;

import com.connecthub.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CleanupSchedulerTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CleanupScheduler cleanupScheduler;

    @Test
    void cleanupUnverifiedAccounts_deletesAccountsOlderThanTwentyFourHours() {
        LocalDateTime before = LocalDateTime.now().minusHours(24).minusMinutes(1);

        cleanupScheduler.cleanupUnverifiedAccounts();

        ArgumentCaptor<LocalDateTime> thresholdCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).deleteByEmailVerifiedFalseAndCreatedAtBefore(thresholdCaptor.capture());
        assertThat(thresholdCaptor.getValue()).isAfter(before);
        assertThat(thresholdCaptor.getValue()).isBefore(LocalDateTime.now().minusHours(23).minusMinutes(59));
    }
}
