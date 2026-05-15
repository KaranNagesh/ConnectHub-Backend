package com.connecthub.room.repository;
import com.connecthub.room.entity.RoomMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface RoomMemberRepository extends JpaRepository<RoomMember, Integer> {
    List<RoomMember> findByRoomId(String roomId);
    List<RoomMember> findByUserId(int userId);
    Optional<RoomMember> findByRoomIdAndUserId(String roomId, int userId);
    boolean existsByRoomIdAndUserId(String roomId, int userId);
    int countByRoomId(String roomId);
    void deleteByRoomIdAndUserId(String roomId, int userId);
    void deleteByRoomId(String roomId);
    void deleteByUserId(int userId);
    @Query("SELECT rm.roomId FROM RoomMember rm WHERE rm.userId = :uid") List<String> findRoomIdsByUserId(@Param("uid") int uid);
}
