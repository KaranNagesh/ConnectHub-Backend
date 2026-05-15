package com.connecthub.auth.listener;

import com.connecthub.auth.entity.User;
import com.connecthub.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaSubscriptionListenerTest {

    @Mock UserRepository userRepository;
    @Mock ObjectMapper objectMapper;
    @Mock com.connecthub.auth.service.UserProfileCacheService profileCache;

    @InjectMocks KafkaSubscriptionListener listener;

    @Test
    void processSubscriptionStatus_updatesUserTier() throws Exception {
        String json = "{\"userId\":5,\"status\":\"PRO\"}";
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", 5);
        payload.put("status", "PRO");

        when(objectMapper.readValue(eq(json), eq(Map.class))).thenReturn(payload);
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(payload);

        User user = User.builder().build();
        when(userRepository.findById(5)).thenReturn(Optional.of(user));

        listener.processSubscriptionStatus(json, "user.subscription.status", 0, 0L);

        verify(userRepository).save(user);
        verify(profileCache).evict(5);
        assert "PRO".equals(user.getSubscriptionTier());
    }

    @Test
    void processSubscriptionStatus_userNotFound_doesNotThrow() throws Exception {
        String json = "{\"userId\":999,\"status\":\"PRO\"}";
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", 999);
        payload.put("status", "PRO");

        when(objectMapper.readValue(eq(json), eq(Map.class))).thenReturn(payload);
        when(objectMapper.convertValue(any(), eq(Map.class))).thenReturn(payload);
        when(userRepository.findById(999)).thenReturn(Optional.empty());

        listener.processSubscriptionStatus(json, "user.subscription.status", 0, 1L);

        verify(userRepository, never()).save(any());
    }

    @Test
    void handleDlq_logsWithoutThrowing() {
        listener.handleDlq("{}", "user.subscription.status.dlq", 0L);
    }
}
