package com.connecthub.notification.service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Sends SMS messages via the Twilio API.
 *
 * On startup the Twilio SDK is initialised with the account credentials
 * injected from application.yml / environment variables.
 *
 * If the credentials are blank (e.g. in a test profile), sending is
 * skipped with a warning log so the rest of the application still works.
 */
@Service
@Slf4j
public class SmsSender {

    @Value("${twilio.account-sid:}")
    private String accountSid;

    @Value("${twilio.auth-token:}")
    private String authToken;

    @Value("${twilio.from-number:}")
    private String fromNumber;

    private boolean configured = false;

    @PostConstruct
    void init() {
        if (accountSid != null && !accountSid.isBlank()
                && authToken != null && !authToken.isBlank()
                && fromNumber != null && !fromNumber.isBlank()) {
            Twilio.init(accountSid, authToken);
            configured = true;
            log.info("Twilio SMS initialised (from={})", fromNumber);
        } else {
            log.warn("Twilio SMS NOT configured — SMS OTPs will only be logged");
        }
    }

    /**
     * Send an SMS message asynchronously.
     *
     * @param to   recipient phone number in E.164 format (e.g. +919876543210)
     * @param body message text
     */
    @Async
    public void send(String to, String body) {
        if (!configured) {
            log.warn("Twilio not configured — SMS to {} skipped. Body: {}", to, body);
            return;
        }
        try {
            Message message = Message.creator(
                    new PhoneNumber(to),
                    new PhoneNumber(fromNumber),
                    body
            ).create();
            log.info("SMS sent to {} (sid={})", to, message.getSid());
        } catch (Exception e) {
            log.error("Failed to send SMS to {}: {}", to, e.getMessage());
        }
    }
}
