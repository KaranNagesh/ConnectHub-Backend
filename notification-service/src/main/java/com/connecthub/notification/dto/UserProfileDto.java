package com.connecthub.notification.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserProfileDto {
    private Integer userId;
    private String username;
    private String email;
    private String fullName;
    private String status;
    private LocalDateTime lastSeenAt;
}
