package com.connecthub.notification.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SmsSenderTest {

    @Test
    void send_unconfigured_skipsSilently() {
        SmsSender sender = new SmsSender();
        ReflectionTestUtils.setField(sender, "configured", false);
        
        assertDoesNotThrow(() -> sender.send("+123456", "Hello"));
    }

    @Test
    void init_withoutCredentials_skipsConfiguration() {
        SmsSender sender = new SmsSender();
        sender.init();
        
        assertDoesNotThrow(() -> sender.send("+123456", "Hello"));
    }
}
