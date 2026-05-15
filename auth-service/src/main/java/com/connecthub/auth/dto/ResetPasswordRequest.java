package com.connecthub.auth.dto;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ResetPasswordRequest {
    @NotBlank private String resetToken;

    @NotBlank @Size(min = 8, max = 72)
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&#^()\\-_+=])[A-Za-z\\d@$!%*?&#^()\\-_+=]{8,72}$",
             message = "Password must contain at least one uppercase, one lowercase, one digit, and one special character")
    private String newPassword;
}
