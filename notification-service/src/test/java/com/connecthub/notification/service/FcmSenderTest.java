package com.connecthub.notification.service;

import com.connecthub.notification.entity.DeviceToken;
import com.connecthub.notification.repository.DeviceTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FcmSenderTest {

    @Mock
    private DeviceTokenRepository deviceTokenRepository;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> tokenResponse;

    @Mock
    private HttpResponse<String> fcmResponse;

    private FcmSender fcmSender;

    @BeforeEach
    void setUp() {
        fcmSender = new FcmSender(deviceTokenRepository, new ObjectMapper(), httpClient);
    }

    @Test
    void sendToUser_ignoresNullUserId() {
        fcmSender.sendToUser(null, "Title", "Body", Map.of());

        verifyNoInteractions(deviceTokenRepository, httpClient);
    }

    @Test
    void sendToUser_skipsWhenFirebaseIsNotConfigured() {
        fcmSender.sendToUser(7, "Title", "Body", Map.of());

        verify(deviceTokenRepository, never()).findByUserId(7);
        verifyNoInteractions(httpClient);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sendToUser_sendsPushMessagesAndReusesAccessToken() throws Exception {
        configureFirebase();
        when(deviceTokenRepository.findByUserId(7)).thenReturn(List.of(
                DeviceToken.builder().token("device-1").build(),
                DeviceToken.builder().token("device-2").build()));
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn("{\"access_token\":\"access-1\",\"expires_in\":3600}");
        when(fcmResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(tokenResponse, fcmResponse, fcmResponse);

        fcmSender.sendToUser(7, null, null, null);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, org.mockito.Mockito.times(3))
                .send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));

        List<HttpRequest> requests = requestCaptor.getAllValues();
        assertThat(requests.get(0).uri().toString()).isEqualTo("https://oauth2.googleapis.com/token");
        assertThat(requests.get(1).uri().toString()).contains("/projects/connecthub-test/messages:send");
        assertThat(requests.get(2).uri().toString()).contains("/projects/connecthub-test/messages:send");
        assertThat(requests.get(1).headers().firstValue("Authorization")).contains("Bearer access-1");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sendToUser_handlesTokenRequestFailureWithoutThrowing() throws Exception {
        configureFirebase();
        when(deviceTokenRepository.findByUserId(7)).thenReturn(List.of(DeviceToken.builder().token("device-1").build()));
        when(tokenResponse.statusCode()).thenReturn(500);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(tokenResponse);

        fcmSender.sendToUser(7, "Title", "Body", Map.of("roomId", "r1"));

        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sendToUser_handlesFcmErrorResponseWithoutThrowing() throws Exception {
        configureFirebase();
        when(deviceTokenRepository.findByUserId(7)).thenReturn(List.of(DeviceToken.builder().token("device-1").build()));
        when(tokenResponse.statusCode()).thenReturn(200);
        when(tokenResponse.body()).thenReturn("{\"access_token\":\"access-1\",\"expires_in\":3600}");
        when(fcmResponse.statusCode()).thenReturn(400);
        when(fcmResponse.body()).thenReturn("bad request");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(tokenResponse, fcmResponse);

        fcmSender.sendToUser(7, "Title", "Body", Map.of("roomId", "r1"));

        verify(httpClient, org.mockito.Mockito.times(2))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private void configureFirebase() throws Exception {
        ReflectionTestUtils.setField(fcmSender, "projectId", "connecthub-test");
        ReflectionTestUtils.setField(fcmSender, "clientEmail", "firebase@example.com");
        ReflectionTestUtils.setField(fcmSender, "privateKeyPem", privateKeyPem().replace("\n", "\\n"));
    }

    private String privateKeyPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        String encoded = java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----";
    }
}
