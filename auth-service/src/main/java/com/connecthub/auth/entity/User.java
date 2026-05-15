package com.connecthub.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer userId;

    @Column(unique = true, nullable = false, length = 30)
    private String username;

    @Column(unique = true, nullable = false, length = 100)
    private String email;

    @Column(length = 255)
    private String passwordHash;

    @Column(length = 100)
    private String fullName;

    @Column(length = 20)
    private String phoneNumber;

    @Column(length = 500)
    private String avatarUrl;

    @Column(length = 200)
    private String bio;

    @Column(nullable = false, length = 20) @Builder.Default
    private String status = "OFFLINE";

    @Column(nullable = false, length = 20) @Builder.Default
    private String provider = "LOCAL";

    private String providerId;

    @Column(nullable = false) @Builder.Default
    private boolean active = true;

    @Column(nullable = false) @Builder.Default
    private boolean emailVerified = false;

    @Column(nullable = false) @Builder.Default
    private boolean phoneVerified = false;

    @Column(nullable = false, length = 20) @Builder.Default
    private String role = "USER";

    @Column(nullable = false, length = 20) @Builder.Default
    private String subscriptionTier = "FREE";

    private LocalDateTime lastSeenAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
