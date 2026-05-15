package com.connecthub.message.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity @Table(name = "message_reactions", uniqueConstraints = @UniqueConstraint(columnNames = {"messageId","userId","emoji"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MessageReaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long reactionId;
    @Column(nullable = false, length = 36) private String messageId;
    @Column(nullable = false) private Integer userId;
    @Column(nullable = false, length = 20) private String emoji;
    @CreationTimestamp private LocalDateTime createdAt;
}
