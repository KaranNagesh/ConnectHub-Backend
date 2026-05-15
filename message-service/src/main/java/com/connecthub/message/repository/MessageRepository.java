package com.connecthub.message.repository;
import com.connecthub.message.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, String> {
    // Cursor-based: get messages older than cursor
    List<Message> findByRoomIdAndIsDeletedFalseAndSentAtBeforeOrderBySentAtDesc(String roomId, LocalDateTime before, Pageable pageable);
    // First page (no cursor)
    List<Message> findByRoomIdAndIsDeletedFalseOrderBySentAtDesc(String roomId, Pageable pageable);
    long countByRoomIdAndSentAtAfterAndIsDeletedFalse(String roomId, LocalDateTime after);
    long countByRoomIdAndIsDeletedFalse(String roomId);

    long countByIsDeletedFalse();
    long countBySentAtAfterAndIsDeletedFalse(LocalDateTime after);

    @Query("""
            SELECT FUNCTION('DATE', m.sentAt), COUNT(m)
            FROM Message m
            WHERE m.isDeleted = false AND m.sentAt >= :since
            GROUP BY FUNCTION('DATE', m.sentAt)
            ORDER BY FUNCTION('DATE', m.sentAt)
            """)
    List<Object[]> countMessagesPerDaySince(@Param("since") LocalDateTime since);

    @Query("SELECT m FROM Message m WHERE m.roomId = :rid AND m.isDeleted = false AND LOWER(m.content) LIKE LOWER(CONCAT('%',:kw,'%')) ORDER BY m.sentAt DESC")
    List<Message> searchInRoom(@Param("rid") String roomId, @Param("kw") String keyword);
    void deleteByRoomId(String roomId);
    void deleteBySenderId(int senderId);
}
