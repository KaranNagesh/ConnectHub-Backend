package com.connecthub.payment.listener;

import com.connecthub.payment.entity.Subscription;
import com.connecthub.payment.repository.PaymentRepository;
import com.connecthub.payment.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserDeletionListener {

    private final SubscriptionRepository subscriptionRepository;
    private final PaymentRepository paymentRepository;

    @KafkaListener(topics = "auth.user.deleted", groupId = "payment-service-group")
    @Transactional
    public void onUserDeleted(String userIdStr) {
        try {
            int userId = Integer.parseInt(userIdStr);
            log.warn("USER_DELETED event received for userId: {}. Extracting financial records.", userId);

            Optional<Subscription> sub = subscriptionRepository.findByUserId(userId);
            if (sub.isPresent()) {
                paymentRepository.deleteBySubscriptionId(sub.get().getId());
                subscriptionRepository.deleteByUserId(userId);
                log.info("Successfully wiped subscription and linked payments for user {}.", userId);
            } else {
                log.info("User {} had no subscription records. Skipping.", userId);
            }
        } catch (NumberFormatException e) {
            log.error("Invalid userId format in USER_DELETED event: {}", userIdStr);
        } catch (Exception e) {
            log.error("Failed to delete financial records for user {}: {}", userIdStr, e.getMessage());
            throw e; // Retry later
        }
    }
}
