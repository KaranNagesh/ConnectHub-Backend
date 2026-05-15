package com.connecthub.media.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;
import java.time.LocalDateTime;

@Entity @Table(name = "media_files")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MediaFile {
    @Id @GeneratedValue(generator = "uuid2") @GenericGenerator(name = "uuid2", strategy = "uuid2") @Column(length = 36) private String mediaId;
    @Column(nullable = false) private Integer uploaderId;
    @Column(length = 36) private String roomId;
    @Column(length = 36) private String messageId;
    @Column(nullable = false, length = 255) private String filename;
    @Column(nullable = false, length = 255) private String originalName;
    @Column(nullable = false, length = 1000) private String url;
    @Column(length = 1000) private String thumbnailUrl;
    @Column(nullable = false, length = 100) private String mimeType;
    private long sizeKb;
    @CreationTimestamp @Column(updatable = false) private LocalDateTime uploadedAt;
}
