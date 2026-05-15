package com.connecthub.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long auditId;
    @Column(nullable = false) private Integer actorId;
    @Column(nullable = false, length = 50) private String action;
    @Column(length = 50) private String entityType;
    @Column(length = 36) private String entityId;
    @Column(length = 500) private String details;
    @Column(length = 45) private String ipAddress;
    @CreationTimestamp @Column(updatable = false) private LocalDateTime createdAt;
}
