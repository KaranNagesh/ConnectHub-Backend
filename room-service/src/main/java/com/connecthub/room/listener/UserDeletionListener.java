package com.connecthub.room.listener;

import com.connecthub.room.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserDeletionListener {

    private final RoomMemberRepository roomMemberRepository;

    @KafkaListener(topics = "auth.user.deleted", groupId = "room-service-group")
    @Transactional
    public void onUserDeleted(String userIdStr) {
        try {
            int userId = Integer.parseInt(userIdStr);
            log.warn("USER_DELETED event received for userId: {}. Evicting from all rooms/DMs.", userId);

            roomMemberRepository.deleteByUserId(userId);

            log.info("Successfully evicted user {} from all rooms.", userId);
        } catch (NumberFormatException e) {
            log.error("Invalid userId format in USER_DELETED event: {}", userIdStr);
        } catch (Exception e) {
            log.error("Failed to delete room memberships for user {}: {}", userIdStr, e.getMessage());
            throw e; // Retry later
        }
    }
}
