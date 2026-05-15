package com.connecthub.auth.dto;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OAuth2CallbackRequest {
    @NotBlank private String code;
    private String redirectUri;
}
