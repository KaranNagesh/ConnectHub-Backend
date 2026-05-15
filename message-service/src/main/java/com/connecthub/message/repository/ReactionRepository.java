package com.connecthub.message.repository;
import com.connecthub.message.entity.MessageReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReactionRepository extends JpaRepository<MessageReaction, Long> {
    List<MessageReaction> findByMessageId(String messageId);
    boolean existsByMessageIdAndUserIdAndEmoji(String messageId, int userId, String emoji);
    void deleteByMessageIdAndUserIdAndEmoji(String messageId, int userId, String emoji);
    void deleteByUserId(int userId);
}
