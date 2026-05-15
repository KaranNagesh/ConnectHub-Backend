package com.connecthub.auth.service;

import com.connecthub.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class CleanupScheduler {

    private final UserRepository userRepository;

    @Scheduled(fixedRate = 3600000) // every hour
    @Transactional
    public void cleanupUnverifiedAccounts() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(24);
        userRepository.deleteByEmailVerifiedFalseAndCreatedAtBefore(threshold);
        log.info("Cleaned up unverified accounts older than 24 hours");
    }
}
