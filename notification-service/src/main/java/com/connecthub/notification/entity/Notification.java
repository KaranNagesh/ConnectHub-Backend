package com.connecthub.notification.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity @Table(name = "notifications")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Integer notificationId;
    @Column(nullable = false) private Integer recipientId;
    private Integer actorId;
    @Column(nullable = false, length = 30) private String type;
    @Column(length = 200) private String title;
    @Column(length = 500) private String message;
    @Column(length = 36) private String roomId;
    @Column(length = 36) private String messageId;
    @Column(nullable = false) @Builder.Default private boolean isRead = false;
    @CreationTimestamp @Column(updatable = false) private LocalDateTime createdAt;
}
