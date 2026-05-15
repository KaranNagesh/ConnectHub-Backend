package com.connecthub.auth.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank @Size(min = 3, max = 30)
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Username must be alphanumeric with underscores only")
    private String username;

    @NotBlank @Email(message = "Must be a valid email address") @Size(max = 100)
    private String email;

    @NotBlank
    @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&#^()\\-_+=])[A-Za-z\\d@$!%*?&#^()\\-_+=]{8,72}$",
             message = "Password must contain at least one uppercase, one lowercase, one digit, and one special character")
    private String password;

    @Size(min = 2, max = 100) private String fullName;

    @Pattern(regexp = "^$|^\\+?[1-9]\\d{6,14}$", message = "Must be a valid phone number")
    private String phoneNumber;
}
