package com.connecthub.payment.controller;

import com.connecthub.payment.service.SubscriptionService;
import com.razorpay.Utils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Razorpay webhook receiver.
 *
 * Security:
 *  1. HMAC-SHA256 signature is verified against ${razorpay.webhook-secret} before
 *     any business logic runs — invalid signatures return 400 immediately.
 *  2. The endpoint is excluded from JWT authentication (configured in SecurityConfig)
 *     because Razorpay cannot send a user JWT.
 *
 * Handled events:
 *  - subscription.activated  → set status=ACTIVE, publish Kafka PRO event
 *  - subscription.cancelled  → set status=CANCELLED, publish Kafka FREE event
 *  - subscription.completed  → set status=EXPIRED, publish Kafka FREE event
 *  - payment.captured        → record Payment(CAPTURED)
 *  - payment.failed          → record Payment(FAILED)
 */
@RestController
@RequestMapping("/api/v1/payments/webhook")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Webhook", description = "Razorpay webhook endpoint — do not call directly")
public class WebhookController {

    private final SubscriptionService subscriptionService;

    @Value("${razorpay.webhook-secret}")
    private String webhookSecret;

    @PostMapping
    @Operation(summary = "Razorpay webhook receiver")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("X-Razorpay-Signature") String signature) {

        // 1. Verify HMAC signature — reject unverified payloads immediately
        try {
            boolean valid = Utils.verifyWebhookSignature(payload, signature, webhookSecret);
            if (!valid) {
                log.warn("Rejected webhook — invalid signature");
                return ResponseEntity.badRequest().body("Invalid signature");
            }
        } catch (Exception e) {
            log.warn("Webhook signature verification failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Signature verification error");
        }

        // 2. Parse and dispatch
        try {
            JSONObject json    = new JSONObject(payload);
            String     event   = json.getString("event");
            JSONObject eventPayload = json.getJSONObject("payload");

            log.info("Received verified Razorpay webhook: {}", event);
            subscriptionService.handleWebhookEvent(event, eventPayload);
            return ResponseEntity.ok("OK");

        } catch (Exception e) {
            log.warn("Webhook processing error: {}", e.getMessage());
            // Return 200 to prevent Razorpay from retrying on our processing errors
            // (signature is already verified — data integrity is intact)
            return ResponseEntity.accepted().body("Accepted with processing error - check logs");
        }
    }
}
