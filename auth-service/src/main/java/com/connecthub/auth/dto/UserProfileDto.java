package com.connecthub.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Safe public DTO for user profiles — never exposes passwordHash or OAuth provider secrets.
 */
@Data @AllArgsConstructor @NoArgsConstructor @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserProfileDto {
    private Integer userId;
    private String username;
    private String email;
    private String fullName;
    private String phoneNumber;
    private String avatarUrl;
    private String bio;
    private String status;
    private String role;
    private String subscriptionTier;
    private boolean emailVerified;
    private boolean phoneVerified;
    private LocalDateTime lastSeenAt;
    private LocalDateTime createdAt;
}
