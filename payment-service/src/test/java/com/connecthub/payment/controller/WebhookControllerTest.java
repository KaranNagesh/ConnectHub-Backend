package com.connecthub.payment.controller;

import com.connecthub.payment.service.SubscriptionService;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookControllerTest {

    @Mock
    private SubscriptionService subscriptionService;

    @InjectMocks
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "webhookSecret", "secret");
    }

    @Test
    void handleWebhook_invalidSignature_returnsBadRequest() {
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyWebhookSignature("payload", "sig", "secret")).thenReturn(false);

            ResponseEntity<String> res = controller.handleWebhook("payload", "sig");

            assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
            assertEquals("Invalid signature", res.getBody());
        }
    }

    @Test
    void handleWebhook_validSignatureAndValidPayload_returnsOk() {
        String payload = "{\"event\":\"subscription.activated\", \"payload\":{}}";

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyWebhookSignature(payload, "sig", "secret")).thenReturn(true);

            ResponseEntity<String> res = controller.handleWebhook(payload, "sig");

            assertEquals(HttpStatus.OK, res.getStatusCode());
            verify(subscriptionService).handleWebhookEvent(eq("subscription.activated"), any(JSONObject.class));
        }
    }

    @Test
    void handleWebhook_verifyThrows_returnsBadRequest() {
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyWebhookSignature(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("err"));

            ResponseEntity<String> res = controller.handleWebhook("payload", "sig");

            assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
            assertEquals("Signature verification error", res.getBody());
        }
    }

    @Test
    void handleWebhook_processingThrows_returnsAcceptedWithWarning() {
        String payload = "{\"invalid\":\"json\"}";

        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyWebhookSignature(payload, "sig", "secret")).thenReturn(true);

            ResponseEntity<String> res = controller.handleWebhook(payload, "sig");

            assertEquals(HttpStatus.ACCEPTED, res.getStatusCode());
            assertEquals("Accepted with processing error - check logs", res.getBody());
        }
    }
}
