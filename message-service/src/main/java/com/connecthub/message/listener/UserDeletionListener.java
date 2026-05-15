package com.connecthub.message.listener;

import com.connecthub.message.repository.MessageRepository;
import com.connecthub.message.repository.ReactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserDeletionListener {

    private final MessageRepository messageRepository;
    private final ReactionRepository reactionRepository;

    @KafkaListener(topics = "auth.user.deleted", groupId = "message-service-group")
    @Transactional
    public void onUserDeleted(String userIdStr) {
        try {
            int userId = Integer.parseInt(userIdStr);
            log.warn("USER_DELETED event received for userId: {}. Executing hard delete of messages and reactions.", userId);
            
            messageRepository.deleteBySenderId(userId);
            reactionRepository.deleteByUserId(userId);
            
            log.info("Successfully wiped all messages and reactions for user {}.", userId);
        } catch (NumberFormatException e) {
            log.error("Invalid userId format in USER_DELETED event: {}", userIdStr);
        } catch (Exception e) {
            log.error("Failed to delete messages for user {}: {}", userIdStr, e.getMessage());
            throw e; // Let Kafka retry if needed depending on exception
        }
    }
}
