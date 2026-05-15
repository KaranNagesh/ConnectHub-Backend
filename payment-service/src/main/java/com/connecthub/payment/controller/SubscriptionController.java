package com.connecthub.payment.controller;

import com.connecthub.payment.dto.CheckoutConfigResponse;
import com.connecthub.payment.dto.ConfirmSubscriptionRequest;
import com.connecthub.payment.dto.CreateSubscriptionRequest;
import com.connecthub.payment.dto.PaymentResponse;
import com.connecthub.payment.dto.PlanResponse;
import com.connecthub.payment.dto.SubscriptionResponse;
import com.connecthub.payment.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Subscription management endpoints.
 * All routes require authentication (JWT via X-User-Id header injected by gateway).
 */
@RestController
@RequestMapping("/api/v1/payments/subscription")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Subscription", description = "Manage ConnectHub PRO subscriptions via Razorpay")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @Value("${razorpay.key-id}")
    private String razorpayKeyId;

    @Value("${connecthub.payment.pro-plan-id:plan_SleLBMz3c9hwjS}")
    private String proPlanId;

    @Value("${connecthub.payment.pro-price:199}")
    private BigDecimal proPrice;

    /**
     * Returns public checkout metadata. This keeps the Angular app aligned with
     * the same Razorpay key and plan IDs used by payment-service.
     */
    @GetMapping({"/checkout-config", "/config"})
    @Operation(summary = "Get checkout config")
    public ResponseEntity<CheckoutConfigResponse> getCheckoutConfig() {
        return ResponseEntity.ok(CheckoutConfigResponse.builder()
                .razorpayKeyId(razorpayKeyId)
                .plans(plans())
                .build());
    }

    @GetMapping("/plans")
    @Operation(summary = "Get subscription plans")
    public ResponseEntity<List<PlanResponse>> getPlans() {
        return ResponseEntity.ok(plans());
    }

    /**
     * Creates (or returns existing) Razorpay subscription for the calling user.
     * The returned subscriptionId is passed to Razorpay Checkout on the frontend.
     */
    @PostMapping("/create")
    @Operation(summary = "Create subscription", description = "Initiates a new Razorpay subscription for the authenticated user")
    public ResponseEntity<SubscriptionResponse> createSubscription(
            @RequestHeader("X-User-Id") Integer userId,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail,
            @Valid @RequestBody CreateSubscriptionRequest req) {

        req.setUserId(userId);
        SubscriptionResponse response = subscriptionService.createSubscription(
                userId, req.getPlanId(), req.getTotalCount(), userEmail);
        return ResponseEntity.ok(response);
    }

    /**
     * Confirms a successful Razorpay checkout response and updates the local row.
     * This keeps local development working even when Razorpay webhooks cannot hit localhost.
     */
    @PostMapping("/confirm")
    @Operation(summary = "Confirm subscription checkout")
    public ResponseEntity<SubscriptionResponse> confirmSubscription(
            @RequestHeader("X-User-Id") Integer userId,
            @Valid @RequestBody ConfirmSubscriptionRequest req) {

        SubscriptionResponse response = subscriptionService.confirmCheckout(
                userId,
                req.getRazorpayPaymentId(),
                req.getRazorpaySubscriptionId(),
                req.getRazorpaySignature());
        return ResponseEntity.ok(response);
    }

    /**
     * Cancels the current Razorpay subscription for the calling user.
     */
    @PostMapping("/cancel")
    @Operation(summary = "Cancel subscription", description = "Cancels the authenticated user's active Razorpay subscription")
    public ResponseEntity<SubscriptionResponse> cancelSubscription(
            @RequestHeader("X-User-Id") Integer userId,
            @RequestBody(required = false) Map<String, String> request) {

        String razorpaySubId = request != null ? request.get("razorpaySubId") : null;
        SubscriptionResponse response = subscriptionService.cancelUserSubscription(userId, razorpaySubId);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns the current subscription status for the calling user.
     */
    @GetMapping("/status")
    @Operation(summary = "Get subscription status")
    public ResponseEntity<SubscriptionResponse> getStatus(
            @RequestHeader("X-User-Id") Integer userId) {
        SubscriptionResponse response = subscriptionService.getSubscription(userId)
                .orElseGet(() -> defaultFreeSubscription(userId));
        return ResponseEntity.ok(response);
    }

    /**
     * Returns payment history for the calling user's subscription.
     */
    @GetMapping("/payments")
    @Operation(summary = "Get payment history")
    public ResponseEntity<List<PaymentResponse>> getPaymentHistory(
            @RequestHeader("X-User-Id") Integer userId) {
        List<PaymentResponse> history = subscriptionService.getPaymentHistory(userId);
        return ResponseEntity.ok(history);
    }

    private SubscriptionResponse defaultFreeSubscription(Integer userId) {
        LocalDateTime now = LocalDateTime.now();
        return SubscriptionResponse.builder()
                .userId(userId)
                .plan("FREE")
                .status("ACTIVE")
                .startDate(now)
                .createdAt(now)
                .build();
    }

    private List<PlanResponse> plans() {
        return List.of(
                PlanResponse.builder()
                        .planId("plan_free")
                        .name("Free")
                        .price(BigDecimal.ZERO)
                        .currency("INR")
                        .interval("forever")
                        .description("For trying ConnectHub with core chat limits.")
                        .features(List.of(
                                "5 messages per minute",
                                "5 MB file uploads",
                                "Up to 3 group rooms",
                                "Standard support"))
                        .recommended(false)
                        .build(),
                PlanResponse.builder()
                        .planId(proPlanId)
                        .name("Pro")
                        .price(proPrice)
                        .currency("INR")
                        .interval("month")
                        .description("For active teams that need higher limits and faster workflows.")
                        .features(List.of(
                                "60 messages per minute",
                                "50 MB file uploads",
                                "Unlimited rooms",
                                "Priority support",
                                "Message search",
                                "Media gallery"))
                        .recommended(true)
                        .build());
    }
}
