package com.connecthub.auth.resource;

import com.connecthub.auth.dto.*;
import com.connecthub.auth.service.AuthService;
import com.connecthub.auth.service.ProfileAvatarService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthResourceTest {

    @Mock
    private AuthService authService;

    @Mock
    private ProfileAvatarService profileAvatarService;

    @InjectMocks
    private AuthResource authResource;
    
    private AuthResponse dummyAuth() {
        return new AuthResponse("token", "Bearer", "refresh", 3600L, null);
    }
    
    private <T> ApiResponse<T> dummyApi(T data, String msg) {
        return ApiResponse.ok(msg, data);
    }

    @Test
    void register() {
        RegisterRequest req = new RegisterRequest();
        when(authService.register(req)).thenReturn(dummyApi("Success", "Registered"));

        ResponseEntity<ApiResponse<String>> res = authResource.register(req);

        assertEquals(HttpStatus.CREATED, res.getStatusCode());
        assertEquals("Success", res.getBody().getData());
    }

    @Test
    void verifyRegistrationOtp() {
        OtpVerifyRequest req = new OtpVerifyRequest();
        when(authService.verifyRegistrationOtp(req)).thenReturn(dummyAuth());

        ResponseEntity<AuthResponse> res = authResource.verifyRegistrationOtp(req);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("token", res.getBody().getAccessToken());
    }

    @Test
    void resendRegistrationOtp() {
        when(authService.resendRegistrationOtp("a@b.com")).thenReturn(dummyApi(null, "Sent"));

        ResponseEntity<ApiResponse<Void>> res = authResource.resendRegistrationOtp(Map.of("email", "a@b.com"));

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("Sent", res.getBody().getMessage());
    }

    @Test
    void requestPhoneOtp() {
        PhoneOtpRequest req = new PhoneOtpRequest();
        when(authService.requestPhoneOtp(req)).thenReturn(dummyApi(null, "Sent"));
        assertEquals(HttpStatus.OK, authResource.requestPhoneOtp(req).getStatusCode());
    }

    @Test
    void verifyPhoneOtp() {
        PhoneOtpVerifyRequest req = new PhoneOtpVerifyRequest();
        when(authService.verifyPhoneOtp(req)).thenReturn(dummyApi(null, "Verified"));
        assertEquals(HttpStatus.OK, authResource.verifyPhoneOtp(req).getStatusCode());
    }

    @Test
    void login() {
        LoginRequest req = new LoginRequest();
        when(authService.login(req)).thenReturn(dummyAuth());
        assertEquals(HttpStatus.OK, authResource.login(req).getStatusCode());
    }

    @Test
    void loginAsGuest() {
        when(authService.loginAsGuest()).thenReturn(dummyAuth());
        assertEquals(HttpStatus.OK, authResource.loginAsGuest().getStatusCode());
    }

    @Test
    void requestEmailLoginOtp() {
        EmailLoginOtpRequest req = new EmailLoginOtpRequest();
        when(authService.requestEmailLoginOtp(req)).thenReturn(dummyApi(null, "Sent"));
        assertEquals(HttpStatus.OK, authResource.requestEmailLoginOtp(req).getStatusCode());
    }

    @Test
    void loginWithEmailOtp() {
        OtpVerifyRequest req = new OtpVerifyRequest();
        when(authService.loginWithEmailOtp(req)).thenReturn(dummyAuth());
        assertEquals(HttpStatus.OK, authResource.loginWithEmailOtp(req).getStatusCode());
    }

    @Test
    void requestPhoneLoginOtp() {
        PhoneOtpRequest req = new PhoneOtpRequest();
        when(authService.requestPhoneLoginOtp(req)).thenReturn(dummyApi(null, "Sent"));
        assertEquals(HttpStatus.OK, authResource.requestPhoneLoginOtp(req).getStatusCode());
    }

    @Test
    void loginWithPhoneOtp() {
        PhoneOtpVerifyRequest req = new PhoneOtpVerifyRequest();
        when(authService.loginWithPhoneOtp(req)).thenReturn(dummyAuth());
        assertEquals(HttpStatus.OK, authResource.loginWithPhoneOtp(req).getStatusCode());
    }

    @Test
    void logout() {
        assertEquals(HttpStatus.NO_CONTENT, authResource.logout("token").getStatusCode());
        verify(authService).logout("token");
    }

    @Test
    void refresh() {
        when(authService.refreshToken("refresh")).thenReturn(dummyAuth());
        assertEquals(HttpStatus.OK, authResource.refresh(Map.of("refreshToken", "refresh")).getStatusCode());
    }

    @Test
    void validate() {
        when(authService.validateToken("token")).thenReturn(true);
        assertEquals(HttpStatus.OK, authResource.validate("token").getStatusCode());
    }

    @Test
    void forgotPassword() {
        ForgotPasswordRequest req = new ForgotPasswordRequest();
        when(authService.forgotPassword(req)).thenReturn(dummyApi(null, "Sent"));
        assertEquals(HttpStatus.OK, authResource.forgotPassword(req).getStatusCode());
    }

    @Test
    void verifyResetOtp() {
        OtpVerifyRequest req = new OtpVerifyRequest();
        when(authService.verifyResetOtp(req)).thenReturn(dummyApi("token", "Verified"));
        assertEquals(HttpStatus.OK, authResource.verifyResetOtp(req).getStatusCode());
    }

    @Test
    void resetPassword() {
        ResetPasswordRequest req = new ResetPasswordRequest();
        when(authService.resetPassword(req)).thenReturn(dummyApi(null, "Reset"));
        assertEquals(HttpStatus.OK, authResource.resetPassword(req).getStatusCode());
    }

    @Test
    void oAuth2Callback() {
        OAuth2CallbackRequest req = new OAuth2CallbackRequest();
        when(authService.oAuth2Callback("google", req)).thenReturn(dummyAuth());
        assertEquals(HttpStatus.OK, authResource.oAuth2Callback("google", req).getStatusCode());
    }

    @Test
    void profileManagement() {
        assertEquals(HttpStatus.OK, authResource.getProfile(1).getStatusCode());
        
        UpdateProfileRequest updateReq = new UpdateProfileRequest();
        assertEquals(HttpStatus.FORBIDDEN, authResource.updateProfile(1, 2, "USER", updateReq).getStatusCode());
        assertEquals(HttpStatus.OK, authResource.updateProfile(1, 1, "USER", updateReq).getStatusCode());
        assertEquals(HttpStatus.OK, authResource.updateProfile(1, 2, "ADMIN", updateReq).getStatusCode());
        
        ChangePasswordRequest cp = new ChangePasswordRequest();
        assertEquals(HttpStatus.FORBIDDEN, authResource.changePassword(1, 2, "USER", cp).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, authResource.changePassword(1, 1, "USER", cp).getStatusCode());
    }

    @Test
    void uploadAvatar_forbiddenForOtherUser() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "png".getBytes());

        assertEquals(HttpStatus.FORBIDDEN, authResource.uploadAvatar(1, 2, "USER", file).getStatusCode());
    }

    @Test
    void uploadAvatar_savesAndUpdatesProfileForOwner() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "png".getBytes());
        UserProfileDto profile = new UserProfileDto();
        when(profileAvatarService.saveAvatar(file, 1)).thenReturn("https://cdn/avatar.png");
        when(authService.updateProfile(org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.any(UpdateProfileRequest.class)))
                .thenReturn(profile);

        ResponseEntity<UserProfileDto> response = authResource.uploadAvatar(1, 1, "USER", file);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(profile, response.getBody());
    }

    @Test
    void getAvatar_returnsInlineResource() throws IOException {
        ByteArrayResource resource = new ByteArrayResource("png".getBytes());
        when(profileAvatarService.loadAvatar("avatar.png"))
                .thenReturn(new ProfileAvatarService.AvatarResource(resource, MediaType.IMAGE_PNG));

        var response = authResource.getAvatar("avatar.png");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertEquals(resource, response.getBody());
    }

    @Test
    void searchUsers() {
        when(authService.searchUsers("test")).thenReturn(List.of(new UserProfileDto()));
        assertEquals(HttpStatus.OK, authResource.searchUsers("test").getStatusCode());
    }

    @Test
    void updateStatus() {
        assertEquals(HttpStatus.FORBIDDEN, authResource.updateStatus(1, 2, "USER", Map.of("status", "ACTIVE")).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, authResource.updateStatus(1, 1, "USER", Map.of("status", "ACTIVE")).getStatusCode());
    }
    
    @Test
    void getUsersByIds() {
        when(authService.getUsersByIds(List.of(1))).thenReturn(List.of(new UserProfileDto()));
        assertEquals(HttpStatus.OK, authResource.getUsersByIds(List.of(1)).getStatusCode());
    }
}
