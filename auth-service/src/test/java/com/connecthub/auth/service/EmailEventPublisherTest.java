package com.connecthub.auth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailEventPublisherTest {

    @Mock
    private StringRedisTemplate redis;

    @InjectMocks
    private EmailEventPublisher publisher;

    @Test
    void sendOtpEmail_publishesRegistrationPayload() {
        publisher.sendOtpEmail("user@example.com", "123456", "registration");

        verify(redis).convertAndSend("email:send", "{\"to\":\"user@example.com\",\"otp\":\"123456\",\"purpose\":\"registration\"}");
    }

    @Test
    void sendSmsOtp_publishesSmsPayload() {
        publisher.sendSmsOtp("+911234567890", "654321");

        verify(redis).convertAndSend(contains("email:send"), contains("\"channel\":\"sms\""));
    }

    @Test
    void sendWelcomeEmail_publishesWelcomePayload() {
        publisher.sendWelcomeEmail("user@example.com", "Karan");

        verify(redis).convertAndSend("email:send", "{\"to\":\"user@example.com\",\"username\":\"Karan\",\"purpose\":\"welcome\"}");
    }
}
