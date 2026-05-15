package com.connecthub.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailSenderTest {

    @Mock
    private JavaMailSender mailSender;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private SmsSender smsSender;
    @Mock
    private SpringTemplateEngine templateEngine;

    @InjectMocks
    private EmailSender emailSender;

    @BeforeEach
    void setUp() {
        lenient().when(mailSender.createMimeMessage()).thenReturn(mock(MimeMessage.class));
        lenient().when(templateEngine.process(anyString(), any(Context.class))).thenReturn("<html></html>");
        ReflectionTestUtils.setField(emailSender, "mailFrom", "no-reply@connecthub.local");
    }

    @Test
    void onMessage_smsChannel_routesToSms() throws Exception {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("to", "+123456");
        node.put("purpose", "login");
        node.put("channel", "sms");
        node.put("otp", "123456");

        when(objectMapper.readTree(any(byte[].class))).thenReturn(node);

        emailSender.onMessage(new DefaultMessage("channel".getBytes(), "{}".getBytes()), new byte[0]);

        verify(smsSender).send(eq("+123456"), contains("123456"));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void onMessage_registrationEmail() throws Exception {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("to", "a@b.com");
        node.put("purpose", "registration");
        node.put("otp", "123456");

        when(objectMapper.readTree(any(byte[].class))).thenReturn(node);

        emailSender.onMessage(new DefaultMessage("channel".getBytes(), "{}".getBytes()), new byte[0]);

        verify(templateEngine).process(eq("otp-email"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void onMessage_passwordResetEmail() throws Exception {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("to", "a@b.com");
        node.put("purpose", "password_reset");
        node.put("otp", "123456");

        when(objectMapper.readTree(any(byte[].class))).thenReturn(node);

        emailSender.onMessage(new DefaultMessage("channel".getBytes(), "{}".getBytes()), new byte[0]);

        verify(templateEngine).process(eq("otp-email"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void onMessage_welcomeEmail() throws Exception {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("to", "a@b.com");
        node.put("purpose", "welcome");
        node.put("username", "testuser");

        when(objectMapper.readTree(any(byte[].class))).thenReturn(node);

        emailSender.onMessage(new DefaultMessage("channel".getBytes(), "{}".getBytes()), new byte[0]);

        verify(templateEngine).process(eq("welcome-email"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void onMessage_subscriptionConfirmation_defaultsAreApplied() throws Exception {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("to", "a@b.com");
        node.put("purpose", "subscription_confirmation");

        when(objectMapper.readTree(any(byte[].class))).thenReturn(node);

        emailSender.onMessage(new DefaultMessage("channel".getBytes(), "{}".getBytes()), new byte[0]);

        verify(templateEngine).process(eq("subscription-confirmation-email"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendOtp_unknownPurpose_usesDefaultTemplateFlow() {
        emailSender.sendOtp("a@b.com", "654321", "something_else");

        verify(templateEngine).process(eq("otp-email"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void onMessage_parseFailure_isHandledWithoutSending() throws Exception {
        when(objectMapper.readTree(any(byte[].class))).thenThrow(new RuntimeException("bad json"));

        emailSender.onMessage(new DefaultMessage("channel".getBytes(), "{}".getBytes()), new byte[0]);

        verify(mailSender, never()).send(any(MimeMessage.class));
        verify(smsSender, never()).send(anyString(), anyString());
    }
}
