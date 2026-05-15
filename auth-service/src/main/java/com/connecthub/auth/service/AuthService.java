package com.connecthub.auth.service;

import com.connecthub.auth.dto.*;
import com.connecthub.auth.entity.User;
import java.util.List;


public interface AuthService {
    // Registration
    ApiResponse<String> register(RegisterRequest request);
    AuthResponse verifyRegistrationOtp(OtpVerifyRequest request);
    ApiResponse<Void> resendRegistrationOtp(String email);

    // Phone OTP (used during registration flow)
    ApiResponse<Void> requestPhoneOtp(PhoneOtpRequest request);
    ApiResponse<Void> verifyPhoneOtp(PhoneOtpVerifyRequest request);

    // Login — classic (email/username + password)
    AuthResponse login(LoginRequest request);

    // Guest login
    AuthResponse loginAsGuest();

    // Login — Email + OTP
    ApiResponse<Void> requestEmailLoginOtp(EmailLoginOtpRequest request);
    AuthResponse loginWithEmailOtp(OtpVerifyRequest request);

    // Login — Phone + OTP
    ApiResponse<Void> requestPhoneLoginOtp(PhoneOtpRequest request);
    AuthResponse loginWithPhoneOtp(PhoneOtpVerifyRequest request);

    // Session management
    void logout(String token);
    boolean validateToken(String token);
    AuthResponse refreshToken(String refreshToken);

    // Password recovery
    ApiResponse<Void> forgotPassword(ForgotPasswordRequest request);
    ApiResponse<String> verifyResetOtp(OtpVerifyRequest request);
    ApiResponse<Void> resetPassword(ResetPasswordRequest request);

    // OAuth2
    AuthResponse oAuth2Callback(String provider, OAuth2CallbackRequest request);

    // User management
    User getUserById(int userId);
    UserProfileDto getPublicProfile(int userId);
    UserProfileDto updateProfile(int userId, UpdateProfileRequest request);
    void changePassword(int userId, ChangePasswordRequest request);
    List<UserProfileDto> searchUsers(String query);
    void updateStatus(int userId, String status);
    void updateLastSeen(int userId);
    User suspendUser(int userId);
    User reactivateUser(int userId);
    void deleteUser(int userId);
    List<User> getAllUsers();
    List<UserProfileDto> getUsersByIds(List<Integer> ids);
}
