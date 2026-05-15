package com.connecthub.auth.dto;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileRequest {
    @Size(min = 2, max = 100) private String fullName;
    @Size(min = 3, max = 30) private String username;
    private String avatarUrl;
    @Size(max = 200) private String bio;
    @Pattern(regexp = "^$|^\\+?[1-9]\\d{6,14}$", message = "Must be a valid phone number")
    private String phoneNumber;
}
