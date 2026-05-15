package com.connecthub.auth.dto;
import lombok.*;

@Data @AllArgsConstructor @Builder
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private long expiresIn;
    private UserDto user;

    @Data @AllArgsConstructor @Builder
    public static class UserDto {
        private Integer userId;
        private String username;
        private String email;
        private String fullName;
        private String phoneNumber;
        private String avatarUrl;
        private String status;
        private String role;
        /** FREE | PRO — refreshed on login/refresh; upgrade PRO via payment-service + refresh token */
        private String subscriptionTier;
    }
}
