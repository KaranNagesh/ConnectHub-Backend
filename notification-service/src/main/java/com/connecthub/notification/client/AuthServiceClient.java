package com.connecthub.notification.client;

import com.connecthub.notification.dto.UserProfileDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "auth-service")
public interface AuthServiceClient {

    @GetMapping("/api/v1/auth/profile/{userId}")
    UserProfileDto getProfile(@PathVariable("userId") int userId);
}
