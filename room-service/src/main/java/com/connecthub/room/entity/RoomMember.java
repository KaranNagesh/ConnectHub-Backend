package com.connecthub.room.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity @Table(name = "room_members", uniqueConstraints = @UniqueConstraint(columnNames = {"roomId","userId"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RoomMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Integer memberId;
    @Column(nullable = false, length = 36) private String roomId;
    @Column(nullable = false) private Integer userId;
    @Column(nullable = false, length = 10) @Builder.Default private String role = "MEMBER";
    @CreationTimestamp @Column(updatable = false) private LocalDateTime joinedAt;
    private LocalDateTime lastReadAt;
    @Column(nullable = false) @Builder.Default private boolean isMuted = false;
}
