package com.connecthub.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * EmailEventPublisher — Publishes Email and SMS Notification Events via Redis Pub/Sub
 *
 * PURPOSE:
 *   When auth-service needs to send an email (OTP, welcome message) or an SMS,
 *   it does NOT send the message directly. Instead, it publishes a JSON event
 *   to a Redis pub/sub channel. The notification-service subscribes to that channel
 *   and handles the actual sending.
 *
 *   This decoupled design has several benefits:
 *   - auth-service doesn't need to know how to send emails (no SMTP config here)
 *   - If the notification-service is temporarily down, Redis buffers the messages
 *   - Email/SMS logic can be changed or upgraded in notification-service independently
 *   - auth-service completes its operation immediately without waiting for email delivery
 *
 * HOW IT WORKS:
 *   StringRedisTemplate.convertAndSend(channel, message) publishes the JSON payload
 *   to the Redis "email:send" channel. All subscribers to that channel receive the
 *   message simultaneously. The notification-service has a RedisMessageSubscriber
 *   that listens on this channel and processes the payload.
 *
 * EVENT PAYLOAD FORMAT (JSON string):
 *   OTP email:      { "to": "user@email.com", "otp": "123456", "purpose": "registration" }
 *   SMS OTP:        { "to": "+919876543210", "otp": "123456", "purpose": "sms_otp", "channel": "sms" }
 *   Welcome email:  { "to": "user@email.com", "username": "janedoe", "purpose": "welcome" }
 *
 * NOTE ON SMS:
 *   SMS delivery is routed through the same Redis channel as email events, but with
 *   a "channel": "sms" field so the notification-service can distinguish them.
 *   The actual SMS gateway integration (Twilio, MSG91, etc.) is in notification-service.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailEventPublisher {

    private final StringRedisTemplate redis;

    /**
     * The Redis pub/sub channel name that notification-service subscribes to.
     * Both publisher and subscriber must use the exact same channel name.
     */
    private static final String EMAIL_CHANNEL = "email:send";

    /**
     * sendOtpEmail — publishes an event to send a 6-digit OTP to a user's email.
     * The "purpose" field tells the notification-service which email template to use:
     * "registration" → "Verify your ConnectHub account"
     * "login"        → "Your ConnectHub login code"
     * "password_reset" → "Reset your ConnectHub password"
     */
    public void sendOtpEmail(String to, String otp, String purpose) {
        String payload = String.format(
            "{\"to\":\"%s\",\"otp\":\"%s\",\"purpose\":\"%s\"}", to, otp, purpose
        );
        redis.convertAndSend(EMAIL_CHANNEL, payload);
        log.info("OTP email event published for {} (purpose={})", to, purpose);
    }

    /**
     * sendSmsOtp — publishes an event to send an OTP via SMS.
     * The "channel": "sms" field directs the notification-service to use the
     * SMS gateway instead of the email sender.
     * In production, the notification-service would call the Twilio or MSG91 API
     * with the phone number and OTP from this payload.
     */
    public void sendSmsOtp(String phone, String otp) {
        String payload = String.format(
            "{\"to\":\"%s\",\"otp\":\"%s\",\"purpose\":\"sms_otp\",\"channel\":\"sms\"}", phone, otp
        );
        redis.convertAndSend(EMAIL_CHANNEL, payload);
        log.info("SMS OTP event published for phone: {}", phone);
    }

    /**
     * sendWelcomeEmail — publishes an event to send a welcome email after first login.
     * The notification-service uses the username to personalize the greeting.
     */
    public void sendWelcomeEmail(String to, String username) {
        String payload = String.format(
            "{\"to\":\"%s\",\"username\":\"%s\",\"purpose\":\"welcome\"}", to, username
        );
        redis.convertAndSend(EMAIL_CHANNEL, payload);
    }
}
