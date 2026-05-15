package com.connecthub.notification.repository;

import com.connecthub.notification.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Integer> {
    List<DeviceToken> findByUserId(int userId);
    Optional<DeviceToken> findByToken(String token);
    void deleteByUserIdAndToken(int userId, String token);
}
