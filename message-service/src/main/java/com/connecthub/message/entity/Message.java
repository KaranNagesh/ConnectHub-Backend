package com.connecthub.message.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;
import java.time.LocalDateTime;

@Entity @Table(name = "messages")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Message {
    @Id @GeneratedValue(generator = "uuid2") @GenericGenerator(name = "uuid2", strategy = "uuid2") @Column(length = 36) private String messageId;
    @Column(nullable = false, length = 36) private String roomId;
    @Column(nullable = false) private Integer senderId;
    @Column(columnDefinition = "TEXT") private String content;
    @Column(nullable = false, length = 20) @Builder.Default private String type = "TEXT";
    @Column(length = 500) private String mediaUrl;
    @Column(length = 500) private String thumbnailUrl;
    @Column(length = 36) private String replyToMessageId;
    @Column(nullable = false) @Builder.Default private boolean isEdited = false;
    @Column(nullable = false) @Builder.Default private boolean isDeleted = false;
    @Column(nullable = false, length = 20) @Builder.Default private String deliveryStatus = "SENT";
    @CreationTimestamp @Column(updatable = false) private LocalDateTime sentAt;
    private LocalDateTime editedAt;
}
