package com.connecthub.payment.service;

import com.connecthub.payment.dto.*;
import com.connecthub.payment.entity.Payment;
import com.connecthub.payment.entity.Subscription;
import com.connecthub.payment.exception.PaymentGatewayException;
import com.connecthub.payment.repository.PaymentRepository;
import com.connecthub.payment.repository.SubscriptionRepository;
import com.razorpay.Entity;
import com.razorpay.RazorpayClient;
import com.razorpay.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SubscriptionService — Razorpay Subscription Lifecycle Management
 *
 * PURPOSE:
 *   Manages the full lifecycle of a user's PRO subscription: creation, activation,
 *   cancellation, expiry, and payment recording. This service is the bridge between
 *   Razorpay (the payment gateway) and the ConnectHub database and event system.
 *
 * SUBSCRIPTION FLOW (three phases):
 *   1. CREATION — createSubscription():
 *      The frontend calls this to initiate an upgrade. This service creates a
 *      Razorpay subscription via API and stores a local record with status=PENDING.
 *      The Razorpay subscription ID is returned to the frontend, which uses it to
 *      display the Razorpay payment checkout widget.
 *
 *   2. ACTIVATION — handleWebhookEvent() → activateSubscription():
 *      After the user completes payment, Razorpay sends a "subscription.activated"
 *      webhook. The subscription status is updated to ACTIVE, and a Kafka event is
 *      published to "user.subscription.status" so auth-service can update the user's
 *      subscriptionTier in the database (which flows into their JWT on next login).
 *
 *   3. TERMINATION — handleWebhookEvent() → cancelSubscription() / expireSubscription():
 *      On cancellation or plan completion, the subscription is marked CANCELLED/EXPIRED,
 *      plan is set back to FREE, and a Kafka event downgrades the user's tier.
 *
 * IDEMPOTENCY:
 *   - createSubscription() checks if the user already has an ACTIVE or PENDING PRO
 *     subscription and returns the existing one rather than creating a duplicate.
 *     This handles the case where the frontend calls the API twice (e.g., on retry).
 *   - recordPayment() checks if the Razorpay payment ID is already in the database
 *     before inserting, making webhook replay safe.
 *   - activateSubscription() handles the race condition where Razorpay sends the
 *     webhook before our createSubscription() DB write is committed: if no matching
 *     subscription row is found, it reconstructs one from the "notes.userId" field
 *     that was embedded in the Razorpay subscription at creation time.
 *
 * KAFKA EVENTS:
 *   Every status change publishes to "user.subscription.status" with userId and
 *   new plan ("PRO" or "FREE"). auth-service's KafkaSubscriptionListener consumes
 *   this to update the user entity, so the next JWT refresh reflects the correct tier.
 *
 * AMOUNTS:
 *   Razorpay stores amounts in paise (1 INR = 100 paise). Payment.amount is stored
 *   as BigDecimal with scale=2 using BigDecimal.valueOf(paise, 2) to convert properly.
 *   The frontend divides by 100 to display the human-readable rupee amount.
 */
@SuppressWarnings("null")
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SubscriptionService {

    private final RazorpayClient razorpayClient;
    private final SubscriptionRepository subscriptionRepo;
    private final PaymentRepository paymentRepo;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate redis;

    @Value("${razorpay.key-id}")
    private String razorpayKeyId;

    @Value("${razorpay.key-secret}")
    private String razorpayKeySecret;

    @Value("${connecthub.payment.pending-checkout-timeout-minutes:30}")
    private long pendingCheckoutTimeoutMinutes;

    public SubscriptionResponse confirmCheckout(
            Integer userId,
            String razorpayPaymentId,
            String razorpaySubscriptionId,
            String razorpaySignature) {

        Subscription sub = subscriptionRepo.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("No subscription found for user " + userId));

        String storedSubId = sub.getRazorpaySubId();
        if (storedSubId == null || storedSubId.isBlank()) {
            throw new RuntimeException("No Razorpay subscription id found for user " + userId);
        }
        if (!storedSubId.equals(razorpaySubscriptionId)) {
            throw new RuntimeException("Subscription id does not belong to the authenticated user");
        }

        try {
            JSONObject attributes = new JSONObject();
            attributes.put("razorpay_payment_id", razorpayPaymentId);
            attributes.put("razorpay_subscription_id", storedSubId);
            attributes.put("razorpay_signature", razorpaySignature);

            if (!Utils.verifySubscription(attributes, razorpayKeySecret)) {
                throw new PaymentGatewayException("Razorpay payment signature verification failed", null);
            }

            try {
                com.razorpay.Subscription rzpSub = razorpayClient.subscriptions.fetch(storedSubId);
                String gatewayStatus = razorpayString(rzpSub, "status").toUpperCase();
                if (isGatewaySubscriptionTerminal(gatewayStatus)) {
                    sub.setStatus(normalizeGatewaySubscriptionStatus(gatewayStatus));
                    if (!"ACTIVE".equalsIgnoreCase(sub.getStatus())) {
                        sub.setPlan("FREE");
                    }
                    subscriptionRepo.save(sub);
                    return toResponse(sub);
                }
            } catch (Exception e) {
                log.debug("Checkout signature verified, but subscription {} could not be fetched: {}",
                        storedSubId, e.getMessage());
            }

            Subscription saved = activateSubscriptionRecord(sub);
            recordCheckoutPayment(saved, razorpayPaymentId);
            publishSubscriptionEvent(userId, "PRO");
            log.info("Confirmed checkout payment {} for subscription {}", razorpayPaymentId, storedSubId);
            return toResponse(saved);

        } catch (PaymentGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentGatewayException(userFacingRazorpayMessage(e, "Could not confirm subscription payment"), e);
        }
    }

    /**
     * createSubscription — initiates a new PRO subscription via Razorpay.
     *
     * HOW IT WORKS:
     *   1. Check if the user already has an ACTIVE or PENDING PRO subscription.
     *      If so, return it immediately (idempotent — no duplicate Razorpay calls).
     *   2. Build the Razorpay subscription options: planId, totalCount (billing cycles),
     *      quantity=1, customer_notify=1. Embed userId in the "notes" field so it can
     *      be recovered from the webhook if our DB write hasn't committed yet.
     *   3. Call Razorpay API to create the subscription and get a Razorpay sub ID.
     *   4. Persist a local Subscription record with status=PENDING. If the user has a
     *      previous CANCELLED/EXPIRED/FREE subscription row, reuse it (to respect the
     *      UNIQUE(user_id) constraint) rather than inserting a new one.
     *   5. Return the SubscriptionResponse containing the Razorpay sub ID, which the
     *      frontend passes to the Razorpay checkout widget.
     *
     * @param planId      Razorpay plan ID (configured in Razorpay dashboard)
     * @param totalCount  number of billing cycles (e.g., 12 for annual)
     */
    public SubscriptionResponse createSubscription(Integer userId, String planId, int totalCount, String userEmail) {
        if (planId == null || planId.isBlank() || "plan_free".equalsIgnoreCase(planId)) {
            throw new IllegalArgumentException("A paid Razorpay plan id is required");
        }

        Optional<Subscription> existing = subscriptionRepo.findByUserId(userId);
        log.debug("Creating subscription for user {} with plan {}", userId, planId);
        if (existing.isPresent()) {
            Subscription sub = syncPendingSubscription(existing.get());
            boolean proPlan = !"FREE".equalsIgnoreCase(sub.getPlan());
            boolean activeOrPending = "ACTIVE".equalsIgnoreCase(sub.getStatus())
                    || "PENDING".equalsIgnoreCase(sub.getStatus());
            if (proPlan && activeOrPending) {
                log.info("User {} already has {} subscription {}", userId, sub.getStatus(), sub.getRazorpaySubId());
                return toResponse(sub);
            }
        }

        try {
            JSONObject options = new JSONObject();
            options.put("plan_id", planId);
            options.put("total_count", totalCount);
            options.put("quantity", 1);
            options.put("customer_notify", 1);
            /*
             * Embed userId in Razorpay notes so the webhook handler can reconstruct the
             * subscription record if the webhook arrives before our DB write commits.
             * This handles the rare but real race condition in distributed systems.
             */
            JSONObject notes = new JSONObject();
            notes.put("userId", userId);
            if (userEmail != null && !userEmail.isBlank()) {
                notes.put("userEmail", userEmail);
            }
            options.put("notes", notes);

            com.razorpay.Subscription rzpSub = razorpayClient.subscriptions.create(options);
            String rzpSubId = rzpSub.get("id");

            /*
             * Reuse the existing row if present (previous CANCELLED/FREE plan) to honor
             * the UNIQUE(user_id) constraint rather than inserting a duplicate row.
             */
            Subscription sub = existing.orElseGet(Subscription::new);
            sub.setUserId(userId);
            sub.setUserEmail(userEmail);
            sub.setPlan("PRO");
            sub.setStatus("PENDING");
            sub.setRazorpaySubId(rzpSubId);
            sub.setStartDate(LocalDateTime.now());
            sub.setEndDate(null);

            sub = subscriptionRepo.save(sub);
            log.info("Created Razorpay subscription {} for user {}", rzpSubId, userId);
            return toResponse(sub);

        } catch (Exception e) {
            String message = userFacingRazorpayMessage(e, "Could not initiate subscription");
            log.warn("Failed to create Razorpay subscription for user {}: {}", userId, e.getMessage());
            throw new PaymentGatewayException(message, e);
        }
    }

    /**
     * cancelUserSubscription — cancels the authenticated user's Razorpay subscription.
     *
     * The frontend may send razorpaySubId, but the userId from the gateway is the source
     * of truth. This prevents one user from cancelling another user's subscription.
     */
    public SubscriptionResponse cancelUserSubscription(Integer userId, String razorpaySubId) {
        Subscription sub = subscriptionRepo.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("No subscription found for user " + userId));

        if ("CANCELLED".equalsIgnoreCase(sub.getStatus())
                || "EXPIRED".equalsIgnoreCase(sub.getStatus())
                || "FREE".equalsIgnoreCase(sub.getPlan())) {
            return toResponse(sub);
        }

        String storedRazorpaySubId = sub.getRazorpaySubId();
        if (storedRazorpaySubId == null || storedRazorpaySubId.isBlank()) {
            throw new RuntimeException("No Razorpay subscription id found for user " + userId);
        }

        if (razorpaySubId != null && !razorpaySubId.isBlank()
                && !storedRazorpaySubId.equals(razorpaySubId)) {
            throw new RuntimeException("Subscription id does not belong to the authenticated user");
        }

        try {
            JSONObject options = new JSONObject();
            options.put("cancel_at_cycle_end", false);

            razorpayClient.subscriptions.cancel(storedRazorpaySubId, options);

            sub.setStatus("CANCELLED");
            sub.setEndDate(LocalDateTime.now());
            sub.setPlan("FREE");

            Subscription saved = subscriptionRepo.save(sub);
            publishSubscriptionEvent(userId, "FREE");

            log.info("Subscription {} cancelled by user {}", storedRazorpaySubId, userId);
            return toResponse(saved);

        } catch (Exception e) {
            log.warn("Failed to cancel Razorpay subscription {} for user {}: {}",
                    storedRazorpaySubId, userId, e.getMessage());
            throw new PaymentGatewayException(userFacingRazorpayMessage(e, "Could not cancel subscription"), e);
        }
    }

    /**
     * handleWebhookEvent — routes Razorpay webhook events to the appropriate handler.
     *
     * HOW IT WORKS:
     *   The WebhookController receives raw Razorpay webhooks, verifies the HMAC-SHA256
     *   signature, and calls this method with the event name and parsed payload.
     *   This method dispatches to the correct private handler based on the event type:
     *   - "subscription.activated" → activate the subscription and upgrade the user
     *   - "subscription.cancelled" → cancel and downgrade the user
     *   - "subscription.completed" → plan ended naturally, downgrade the user
     *   - "payment.captured" / "payment.failed" → record the payment transaction
     *   Unknown events are silently ignored (logged at DEBUG level).
     */
    public void handleWebhookEvent(String event, JSONObject payload) {
        log.info("Processing Razorpay webhook event: {}", event);

        switch (event) {
            case "subscription.activated" -> activateSubscription(payload);
            case "subscription.cancelled"  -> cancelSubscription(payload);
            case "subscription.completed"  -> expireSubscription(payload);
            case "payment.captured"        -> recordPayment(payload, "CAPTURED");
            case "payment.failed"          -> recordPayment(payload, "FAILED");
            default -> log.debug("Unhandled webhook event: {}", event);
        }
    }

    /**
     * activateSubscription — handles the "subscription.activated" webhook.
     *
     * HOW IT WORKS:
     *   Finds the local subscription row by matching the Razorpay sub ID. Updates
     *   status to ACTIVE and publishes a Kafka event to upgrade the user to PRO.
     *
     *   If no local row is found (webhook race condition — arrived before our DB write),
     *   reconstructs the subscription from the "notes.userId" field embedded at creation.
     *   This ensures activations are never silently lost even under race conditions.
     */
    private void activateSubscription(JSONObject payload) {
        JSONObject entity = payload.getJSONObject("subscription").getJSONObject("entity");
        String rzpSubId  = entity.getString("id");
        JSONObject notes = entity.optJSONObject("notes");

        subscriptionRepo.findByRazorpaySubId(rzpSubId)
                .ifPresentOrElse(sub -> {
                    sub.setStatus("ACTIVE");
                    sub.setPlan("PRO");
                    subscriptionRepo.save(sub);
                    publishSubscriptionEvent(sub.getUserId(), "PRO");
                    log.info("Subscription {} activated for user {}", rzpSubId, sub.getUserId());
                }, () -> {
                    if (notes != null && notes.has("userId")) {
                        int userId = notes.getInt("userId");
                        Subscription sub = subscriptionRepo.findByUserId(userId)
                                .orElseGet(Subscription::new);
                        sub.setUserId(userId);
                        sub.setUserEmail(notes.optString("userEmail", sub.getUserEmail()));
                        sub.setPlan("PRO");
                        sub.setStatus("ACTIVE");
                        sub.setRazorpaySubId(rzpSubId);
                        sub.setStartDate(LocalDateTime.now());
                        sub.setEndDate(null);
                        subscriptionRepo.save(sub);
                        publishSubscriptionEvent(userId, "PRO");
                    }
                });
    }

    /**
     * cancelSubscription — handles the "subscription.cancelled" webhook.
     * Updates the subscription to CANCELLED, sets endDate to now, resets plan to FREE,
     * and publishes a Kafka event to downgrade the user's tier in auth-service.
     */
    private void cancelSubscription(JSONObject payload) {
        String rzpSubId = payload.getJSONObject("subscription")
                .getJSONObject("entity").getString("id");
        subscriptionRepo.findByRazorpaySubId(rzpSubId)
                .ifPresent(sub -> {
                    sub.setStatus("CANCELLED");
                    sub.setEndDate(LocalDateTime.now());
                    sub.setPlan("FREE");
                    subscriptionRepo.save(sub);
                    publishSubscriptionEvent(sub.getUserId(), "FREE");
                    log.info("Subscription {} cancelled for user {}", rzpSubId, sub.getUserId());
                });
    }

    /**
     * expireSubscription — handles the "subscription.completed" webhook.
     * Triggered when all billing cycles are exhausted (natural plan end, not cancellation).
     * Sets status to EXPIRED and downgrades the user to FREE, same as cancellation.
     */
    private void expireSubscription(JSONObject payload) {
        String rzpSubId = payload.getJSONObject("subscription")
                .getJSONObject("entity").getString("id");
        subscriptionRepo.findByRazorpaySubId(rzpSubId)
                .ifPresent(sub -> {
                    sub.setStatus("EXPIRED");
                    sub.setEndDate(LocalDateTime.now());
                    sub.setPlan("FREE");
                    subscriptionRepo.save(sub);
                    publishSubscriptionEvent(sub.getUserId(), "FREE");
                    log.info("Subscription {} expired for user {}", rzpSubId, sub.getUserId());
                });
    }

    /**
     * recordPayment — persists a payment transaction from a Razorpay webhook.
     *
     * HOW IT WORKS:
     *   1. Extract payment details from the webhook payload (ID, order ID, amount, currency).
     *   2. Idempotency check: if this Razorpay payment ID is already in the database, skip.
     *   3. Link the payment to the local subscription row by matching razorpaySubId.
     *   4. Convert amount from paise to rupees using BigDecimal.valueOf(paise, 2).
     *   5. Persist the Payment row with the given status (CAPTURED or FAILED).
     *
     * Called for both "payment.captured" (successful) and "payment.failed" (declined).
     * Both are stored so the payment history page shows the full transaction log.
     */
    private void recordPayment(JSONObject payload, String status) {
        try {
            JSONObject entity = payload.getJSONObject("payment").getJSONObject("entity");
            String rzpPayId   = entity.getString("id");
            String rzpOrderId = entity.optString("order_id", null);
            long   amountPaisa = entity.getLong("amount");
            String currency   = entity.optString("currency", "INR");

            if (paymentRepo.findByRazorpayPaymentId(rzpPayId).isPresent()) {
                log.debug("Payment {} already recorded — skipping", rzpPayId);
                return;
            }

            String rzpSubId = entity.optString("subscription_id", null);
            if (rzpSubId == null || rzpSubId.isBlank()) {
                log.warn("Payment {} has no Razorpay subscription id; skipping record", rzpPayId);
                return;
            }

            Long subId = subscriptionRepo.findByRazorpaySubId(rzpSubId)
                    .map(Subscription::getId)
                    .orElse(null);
            if (subId == null) {
                log.warn("Payment {} references unknown subscription {}; skipping record", rzpPayId, rzpSubId);
                return;
            }

            Payment payment = Payment.builder()
                    .subscriptionId(subId)
                    .razorpayPaymentId(rzpPayId)
                    .razorpayOrderId(rzpOrderId)
                    .amount(BigDecimal.valueOf(amountPaisa, 2))
                    .currency(currency)
                    .status(status)
                    .build();

            paymentRepo.save(payment);
            log.info("Payment {} recorded with status {}", rzpPayId, status);

            if ("CAPTURED".equals(status)) {
                sendReceiptEmail(subId, amountPaisa);
            }
        } catch (Exception e) {
            log.error("Failed to record payment: {}", e.getMessage());
        }
    }

    private void sendReceiptEmail(Long subscriptionId, long amountPaisa) {
        if (subscriptionId == null) return;
        subscriptionRepo.findById(subscriptionId).ifPresent(sub -> {
            String email = sub.getUserEmail();
            if (email == null || email.isBlank()) return;
            String rupees = String.format("₹%.2f", amountPaisa / 100.0);
            String payload = String.format(
                    "{\"to\":\"%s\",\"purpose\":\"subscription_confirmation\",\"plan\":\"%s\",\"amount\":\"%s\"}",
                    email, sub.getPlan(), rupees);
            try {
                redis.convertAndSend("email:send", payload);
                log.info("Receipt email queued for user {}", sub.getUserId());
            } catch (Exception e) {
                log.warn("Failed to queue receipt email for user {}: {}", sub.getUserId(), e.getMessage());
            }
        });
    }

    /**
     * getSubscription — returns the user's current subscription details, if any.
     * Returns Optional.empty() for users who have never had a subscription row.
     */
    @Transactional
    public Optional<SubscriptionResponse> getSubscription(Integer userId) {
        return subscriptionRepo.findByUserId(userId).map(this::syncPendingSubscription).map(this::toResponse);
    }

    /**
     * getPaymentHistory — returns all payment transactions for a user, newest first.
     * Used by the BillingPage frontend component to show the transaction history table.
     * Returns an empty list if the user has no subscription record.
     */
    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaymentHistory(Integer userId) {
        return subscriptionRepo.findByUserId(userId)
                .map(sub -> paymentRepo.findBySubscriptionIdOrderByCreatedAtDesc(sub.getId())
                        .stream().map(this::toPaymentResponse).toList())
                .orElse(List.of());
    }

    /**
     * publishSubscriptionEvent — publishes a tier change event to Kafka.
     * auth-service's KafkaSubscriptionListener consumes this and updates the user's
     * subscriptionTier field in the database. The user's next JWT refresh will then
     * carry the correct tier in the X-Subscription-Tier claim.
     */
    private void publishSubscriptionEvent(Integer userId, String plan) {
        Map<String, Object> event = new HashMap<>();
        event.put("userId", userId);
        event.put("status", plan);
        kafkaTemplate.send("user.subscription.status", String.valueOf(userId), new JSONObject(event).toString());
        log.debug("Published subscription event userId={} plan={}", userId, plan);
    }

    /** toResponse — maps a Subscription entity to the SubscriptionResponse DTO. */
    private Subscription syncPendingSubscription(Subscription sub) {
        if (!"PRO".equalsIgnoreCase(sub.getPlan())
                || !"PENDING".equalsIgnoreCase(sub.getStatus())
                || sub.getRazorpaySubId() == null
                || sub.getRazorpaySubId().isBlank()) {
            return sub;
        }

        if (hasSuccessfulPayment(sub)) {
            Subscription saved = activateSubscriptionRecord(sub);
            publishSubscriptionEvent(saved.getUserId(), "PRO");
            return saved;
        }

        try {
            com.razorpay.Subscription rzpSub = razorpayClient.subscriptions.fetch(sub.getRazorpaySubId());
            String gatewayStatus = razorpayString(rzpSub, "status").toUpperCase();
            if (isGatewaySubscriptionUsable(gatewayStatus) || hasPaidGatewayCycle(rzpSub)) {
                Subscription saved = activateSubscriptionRecord(sub);
                publishSubscriptionEvent(sub.getUserId(), "PRO");
                return saved;
            }

            String localStatus = normalizeGatewaySubscriptionStatus(gatewayStatus);
            if (!localStatus.equalsIgnoreCase(sub.getStatus())) {
                if ("CANCELLED".equalsIgnoreCase(localStatus) || "EXPIRED".equalsIgnoreCase(localStatus)) {
                    return endPendingSubscription(sub, localStatus,
                            "gateway status " + gatewayStatus);
                }
                sub.setStatus(localStatus);
                return subscriptionRepo.save(sub);
            }
        } catch (Exception e) {
            log.debug("Could not sync pending subscription {}: {}", sub.getRazorpaySubId(), e.getMessage());
        }

        if (isPendingCheckoutStale(sub)) {
            return endPendingSubscription(sub, "EXPIRED", "checkout timed out");
        }

        return sub;
    }

    private Subscription activateSubscriptionRecord(Subscription sub) {
        sub.setPlan("PRO");
        sub.setStatus("ACTIVE");
        sub.setEndDate(null);
        if (sub.getStartDate() == null) {
            sub.setStartDate(LocalDateTime.now());
        }
        return subscriptionRepo.save(sub);
    }

    private Subscription endPendingSubscription(Subscription sub, String status, String reason) {
        String previousSubId = sub.getRazorpaySubId();
        sub.setPlan("FREE");
        sub.setStatus(status);
        sub.setEndDate(LocalDateTime.now());
        Subscription saved = subscriptionRepo.save(sub);
        publishSubscriptionEvent(saved.getUserId(), "FREE");
        log.info("Marked pending subscription {} as {} for user {} ({})",
                previousSubId, status, saved.getUserId(), reason);
        return saved;
    }

    private boolean isPendingCheckoutStale(Subscription sub) {
        if (!"PRO".equalsIgnoreCase(sub.getPlan()) || !"PENDING".equalsIgnoreCase(sub.getStatus())) {
            return false;
        }
        LocalDateTime startedAt = sub.getStartDate() != null ? sub.getStartDate() : sub.getCreatedAt();
        if (startedAt == null) {
            return false;
        }
        long timeoutMinutes = Math.max(1, pendingCheckoutTimeoutMinutes);
        return startedAt.plusMinutes(timeoutMinutes).isBefore(LocalDateTime.now());
    }

    private boolean hasSuccessfulPayment(Subscription sub) {
        if (sub.getId() == null) return false;
        return paymentRepo.findBySubscriptionIdOrderByCreatedAtDesc(sub.getId()).stream()
                .anyMatch(payment -> isSuccessfulPaymentStatus(payment.getStatus()));
    }

    private boolean isSuccessfulPaymentStatus(String status) {
        String normalized = status == null ? "" : status.toUpperCase();
        return switch (normalized) {
            case "CAPTURED", "AUTHORIZED", "PAID", "SUCCESS" -> true;
            default -> false;
        };
    }

    private void recordCheckoutPayment(Subscription sub, String razorpayPaymentId) {
        if (razorpayPaymentId == null || razorpayPaymentId.isBlank()
                || paymentRepo.findByRazorpayPaymentId(razorpayPaymentId).isPresent()) {
            return;
        }

        try {
            com.razorpay.Payment rzpPayment = razorpayClient.payments.fetch(razorpayPaymentId);
            long amountPaisa = razorpayLong(rzpPayment, "amount");
            String currency = razorpayString(rzpPayment, "currency");
            String status = razorpayString(rzpPayment, "status").toUpperCase();
            String orderId = razorpayString(rzpPayment, "order_id");

            paymentRepo.save(Payment.builder()
                    .subscriptionId(sub.getId())
                    .razorpayPaymentId(razorpayPaymentId)
                    .razorpayOrderId(orderId)
                    .amount(BigDecimal.valueOf(amountPaisa, 2))
                    .currency(currency.isBlank() || "null".equalsIgnoreCase(currency) ? "INR" : currency)
                    .status(status)
                    .build());
        } catch (Exception e) {
            log.debug("Could not record checkout payment {}: {}", razorpayPaymentId, e.getMessage());
        }
    }

    private boolean isGatewaySubscriptionUsable(String status) {
        return "ACTIVE".equalsIgnoreCase(status) || "AUTHENTICATED".equalsIgnoreCase(status);
    }

    private boolean hasPaidGatewayCycle(com.razorpay.Subscription rzpSub) {
        Object paidCount = razorpayValue(rzpSub, "paid_count");
        if (paidCount instanceof Number number) {
            return number.intValue() > 0;
        }
        try {
            return Integer.parseInt(String.valueOf(paidCount)) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Object razorpayValue(Entity entity, String key) {
        return entity == null || !entity.has(key) ? null : entity.get(key);
    }

    private String razorpayString(Entity entity, String key) {
        Object value = razorpayValue(entity, key);
        return value == null ? "" : value.toString();
    }

    private long razorpayLong(Entity entity, String key) {
        Object value = razorpayValue(entity, key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private boolean isGatewaySubscriptionTerminal(String status) {
        return "CANCELLED".equalsIgnoreCase(status)
                || "COMPLETED".equalsIgnoreCase(status)
                || "EXPIRED".equalsIgnoreCase(status);
    }

    private String normalizeGatewaySubscriptionStatus(String status) {
        String normalized = status == null ? "" : status.toUpperCase();
        return switch (normalized) {
            case "ACTIVE", "AUTHENTICATED" -> "ACTIVE";
            case "CANCELLED" -> "CANCELLED";
            case "COMPLETED", "EXPIRED" -> "EXPIRED";
            default -> "PENDING";
        };
    }

    private SubscriptionResponse toResponse(Subscription s) {
        return SubscriptionResponse.builder()
                .id(s.getId()).userId(s.getUserId()).plan(s.getPlan())
                .status(s.getStatus()).razorpaySubId(s.getRazorpaySubId())
                .startDate(s.getStartDate()).endDate(s.getEndDate())
                .createdAt(s.getCreatedAt()).build();
    }

    /** toPaymentResponse — maps a Payment entity to the PaymentResponse DTO. */
    private PaymentResponse toPaymentResponse(Payment p) {
        return PaymentResponse.builder()
                .id(p.getId()).subscriptionId(p.getSubscriptionId())
                .razorpayPaymentId(p.getRazorpayPaymentId())
                .razorpayOrderId(p.getRazorpayOrderId())
                .amount(p.getAmount()).currency(p.getCurrency())
                .status(p.getStatus()).createdAt(p.getCreatedAt()).build();
    }

    private String userFacingRazorpayMessage(Exception e, String fallback) {
        String raw = e.getMessage() == null ? "" : e.getMessage();
        String lower = raw.toLowerCase();
        if (lower.contains("unauthorized")) {
            return "Razorpay rejected the payment credentials. Check RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET.";
        }
        if (lower.contains("plan")) {
            return "Razorpay rejected the selected plan. Check RAZORPAY_PRO_PLAN_ID.";
        }
        return fallback + ": " + raw;
    }
}
