package com.connecthub.notification.service;

import com.connecthub.notification.entity.DeviceToken;
import com.connecthub.notification.repository.DeviceTokenRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class FcmSender {

    private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";

    private final DeviceTokenRepository deviceTokenRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public FcmSender(DeviceTokenRepository deviceTokenRepository, ObjectMapper objectMapper) {
        this(deviceTokenRepository, objectMapper, HttpClient.newHttpClient());
    }

    FcmSender(DeviceTokenRepository deviceTokenRepository, ObjectMapper objectMapper, HttpClient httpClient) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Value("${firebase.project-id:}")
    private String projectId;

    @Value("${firebase.client-email:}")
    private String clientEmail;

    @Value("${firebase.private-key:}")
    private String privateKeyPem;

    private String cachedAccessToken;
    private long cachedAccessTokenExpiresAt;

    @Async
    public void sendToUser(Integer userId, String title, String body, Map<String, String> data) {
        if (userId == null) return;
        if (!isConfigured()) {
            log.debug("Firebase FCM HTTP v1 is not configured; push notification skipped for user {}", userId);
            return;
        }

        for (DeviceToken token : deviceTokenRepository.findByUserId(userId)) {
            send(token.getToken(), title, body, data);
        }
    }

    private void send(String token, String title, String body, Map<String, String> data) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("token", token);
            message.put("notification", Map.of(
                    "title", title == null ? "ConnectHub" : title,
                    "body", body == null ? "" : body
            ));
            message.put("data", data == null ? Map.of() : data);

            Map<String, Object> payload = Map.of("message", message);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send"))
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("FCM push failed with status {}: {}", response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("FCM push interrupted");
        } catch (Exception e) {
            log.warn("FCM push failed: {}", e.getMessage());
        }
    }

    private synchronized String accessToken() throws Exception {
        long now = Instant.now().getEpochSecond();
        if (cachedAccessToken != null && cachedAccessTokenExpiresAt > now + 60) {
            return cachedAccessToken;
        }

        String assertion = signedJwt(now);
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URI))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("OAuth token request failed with status " + response.statusCode());
        }

        JsonNode json = objectMapper.readTree(response.body());
        cachedAccessToken = json.get("access_token").asText();
        cachedAccessTokenExpiresAt = now + json.path("expires_in").asLong(3600);
        return cachedAccessToken;
    }

    private String signedJwt(long now) throws Exception {
        String header = base64Url(objectMapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String claims = base64Url(objectMapper.writeValueAsBytes(Map.of(
                "iss", clientEmail,
                "scope", FCM_SCOPE,
                "aud", TOKEN_URI,
                "iat", now,
                "exp", now + 3600
        )));
        String unsigned = header + "." + claims;

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey());
        signature.update(unsigned.getBytes(StandardCharsets.UTF_8));
        return unsigned + "." + base64Url(signature.sign());
    }

    private PrivateKey privateKey() throws Exception {
        String normalized = privateKeyPem.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(normalized);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean isConfigured() {
        return projectId != null && !projectId.isBlank()
                && clientEmail != null && !clientEmail.isBlank()
                && privateKeyPem != null && !privateKeyPem.isBlank();
    }
}
