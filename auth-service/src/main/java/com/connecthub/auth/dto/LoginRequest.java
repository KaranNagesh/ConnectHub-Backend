package com.connecthub.auth.dto;
import lombok.Data;

@Data
public class LoginRequest {
    private String email;       // can be email or null
    private String username;    // can be username or null
    private String password;    // required for password-based login
}
