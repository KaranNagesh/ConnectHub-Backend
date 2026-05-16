package com.connecthub.room.repository;
import com.connecthub.room.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RoomRepository extends JpaRepository<Room, String> {
    Optional<Room> findByRoomId(String roomId);
    Optional<Room> findByDmKey(String dmKey);

    @Query("""
        SELECT r
        FROM Room r
        WHERE r.type = 'DM'
          AND EXISTS (
              SELECT 1 FROM RoomMember rm1
              WHERE rm1.roomId = r.roomId AND rm1.userId = :firstUserId
          )
          AND EXISTS (
              SELECT 1 FROM RoomMember rm2
              WHERE rm2.roomId = r.roomId AND rm2.userId = :secondUserId
          )
          AND (
              SELECT COUNT(rm3)
              FROM RoomMember rm3
              WHERE rm3.roomId = r.roomId
          ) = 2
        ORDER BY r.createdAt ASC
        """)
    List<Room> findDirectMessageRooms(@Param("firstUserId") int firstUserId, @Param("secondUserId") int secondUserId);

    long countByCreatedByIdAndType(int createdById, String type);
    long countByType(String type);
    long countByLastMessageAtAfter(LocalDateTime after);
}
