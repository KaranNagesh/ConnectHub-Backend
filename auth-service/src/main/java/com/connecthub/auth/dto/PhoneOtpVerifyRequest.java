package com.connecthub.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PhoneOtpVerifyRequest {
    @NotBlank
    @Pattern(regexp = "^\\+?[1-9]\\d{6,14}$", message = "Must be a valid phone number")
    private String phoneNumber;

    @NotBlank @Size(min = 6, max = 6)
    private String otp;
}
