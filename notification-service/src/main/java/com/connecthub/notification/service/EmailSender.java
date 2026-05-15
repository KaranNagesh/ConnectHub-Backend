package com.connecthub.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

@Component @RequiredArgsConstructor @Slf4j
public class EmailSender implements MessageListener {

    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;
    private final SmsSender smsSender;
    private final SpringTemplateEngine templateEngine;

    @Value("${frontend.url:http://localhost:5173}")
    private String frontendUrl;

    @Value("${spring.mail.username:no.reply.connecthub@gmail.com}")
    private String mailFrom;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode node = objectMapper.readTree(message.getBody());
            String to = node.get("to").asText();
            String purpose = node.get("purpose").asText();
            String channel = node.has("channel") ? node.get("channel").asText() : "email";

            if ("sms".equals(channel)) {
                String otp = node.get("otp").asText();
                String body = "Your ConnectHub verification code is: " + otp + ". It expires in 5 minutes.";
                smsSender.send(to, body);
                log.info("SMS OTP dispatched to {}", to);
                return;
            }

            if ("registration".equals(purpose) || "password_reset".equals(purpose) || "login".equals(purpose)) {
                String otp = node.get("otp").asText();
                sendOtp(to, otp, purpose);
            } else if ("welcome".equals(purpose)) {
                String username = node.get("username").asText();
                sendWelcome(to, username);
            } else if ("subscription_confirmation".equals(purpose)) {
                String plan = node.has("plan") ? node.get("plan").asText() : "PRO";
                String amount = node.has("amount") ? node.get("amount").asText() : null;
                String username = node.has("username") ? node.get("username").asText() : null;
                sendSubscriptionConfirmation(to, plan, amount, username);
            }
        } catch (Exception e) {
            log.error("Failed to process email event", e);
        }
    }

    @Async
    public void sendOtp(String to, String otp, String purpose) {
        String subject;
        String greeting;
        String message;
        String warning = null;
        int expiryMinutes = 5;

        switch (purpose) {
            case "registration":
                subject = "ConnectHub - Verify Your Email";
                greeting = "Welcome to ConnectHub!";
                message = "Here is your verification code:";
                break;
            case "password_reset":
                subject = "ConnectHub - Password Reset";
                greeting = "Hello,";
                message = "We received a request to reset your password. Your reset code is:";
                warning = "If you did not request a password reset, please ignore this email or contact support if you have concerns.";
                expiryMinutes = 10;
                break;
            case "login":
                subject = "ConnectHub - Login Verification Code";
                greeting = "Hello,";
                message = "Your login verification code is:";
                warning = "If you did not request this, someone may be trying to access your account.";
                break;
            default:
                subject = "ConnectHub - Verification Code";
                greeting = "Hello,";
                message = "Your code is:";
                break;
        }

        Context context = new Context();
        context.setVariable("subject", subject);
        context.setVariable("greeting", greeting);
        context.setVariable("message", message);
        context.setVariable("otp", otp);
        context.setVariable("expiryMinutes", expiryMinutes);
        if (warning != null) {
            context.setVariable("warning", warning);
        }

        String htmlBody = templateEngine.process("otp-email", context);
        sendHtml(to, subject, htmlBody);
    }

    @Async
    public void sendWelcome(String to, String username) {
        Context context = new Context();
        context.setVariable("username", username);
        context.setVariable("frontendUrl", frontendUrl);
        String htmlBody = templateEngine.process("welcome-email", context);
        sendHtml(to, "Welcome to ConnectHub!", htmlBody);
    }

    @Async
    public void sendSubscriptionConfirmation(String to, String plan, String amount, String username) {
        Context context = new Context();
        context.setVariable("plan", plan);
        context.setVariable("amount", amount != null ? amount : "—");
        context.setVariable("username", username != null ? username : "there");
        context.setVariable("frontendUrl", frontendUrl);
        String htmlBody = templateEngine.process("subscription-confirmation-email", context);
        sendHtml(to, "Welcome to ConnectHub " + plan + "! 🎉 Here's your receipt", htmlBody);
    }

    @Async
    public void sendMissedDirectMessage(String to, String senderName, String preview, String roomId) {
        String safeSender = HtmlUtils.htmlEscape(senderName != null && !senderName.isBlank() ? senderName : "Someone");
        String safePreview = HtmlUtils.htmlEscape(preview != null ? preview : "");
        String roomUrl = frontendUrl + "/chat" + (roomId != null && !roomId.isBlank() ? "?room=" + roomId : "");
        String htmlBody = """
                <div style="font-family:Arial,sans-serif;line-height:1.5;color:#18212f">
                  <h2 style="margin:0 0 12px">You have a missed direct message</h2>
                  <p><strong>%s</strong> sent you a message while you were offline.</p>
                  <blockquote style="margin:16px 0;padding:12px 14px;border-left:4px solid #3b82f6;background:#f4f7fb">%s</blockquote>
                  <p><a href="%s" style="display:inline-block;padding:10px 14px;border-radius:8px;background:#2563eb;color:white;text-decoration:none">Open ConnectHub</a></p>
                </div>
                """.formatted(safeSender, safePreview, HtmlUtils.htmlEscape(roomUrl));
        sendHtml(to, "Missed direct message on ConnectHub", htmlBody);
    }

    private void sendHtml(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            // true indicates multipart message
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true); // true indicates HTML
            helper.setFrom(mailFrom);
            mailSender.send(message);
            log.info("HTML Email sent to {}: {}", to, subject);
        } catch (Exception e) {
            log.error("Failed to send HTML email to {}: {}", to, e.getMessage());
        }
    }
}
