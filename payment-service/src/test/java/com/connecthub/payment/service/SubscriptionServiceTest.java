package com.connecthub.payment.service;

import com.connecthub.payment.dto.SubscriptionResponse;
import com.connecthub.payment.entity.Payment;
import com.connecthub.payment.entity.Subscription;
import com.connecthub.payment.exception.PaymentGatewayException;
import com.connecthub.payment.repository.PaymentRepository;
import com.connecthub.payment.repository.SubscriptionRepository;
import com.razorpay.PaymentClient;
import com.razorpay.RazorpayClient;
import com.razorpay.SubscriptionClient;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock private RazorpayClient razorpayClient;
    @Mock private SubscriptionClient subscriptionClient;
    @Mock private PaymentClient paymentClient;
    @Mock private SubscriptionRepository subscriptionRepo;
    @Mock private PaymentRepository paymentRepo;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private StringRedisTemplate redis;

    @InjectMocks
    private SubscriptionService svc;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(svc, "razorpayKeyId", "test_key");
        ReflectionTestUtils.setField(svc, "razorpayKeySecret", "secret");
        ReflectionTestUtils.setField(svc, "pendingCheckoutTimeoutMinutes", 30L);
        razorpayClient.subscriptions = subscriptionClient;
        razorpayClient.payments = paymentClient;
        lenient().when(subscriptionRepo.save(any(Subscription.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void confirmCheckout_validSignature_activatesAndRecordsPayment() throws Exception {
        Subscription pending = Subscription.builder()
                .id(70L)
                .userId(7)
                .plan("PRO")
                .status("PENDING")
                .razorpaySubId("sub_1")
                .build();
        when(subscriptionRepo.findByUserId(7)).thenReturn(Optional.of(pending));
        when(subscriptionClient.fetch("sub_1")).thenReturn(razorpaySubscription("sub_1", "authenticated", 0));
        when(paymentRepo.findByRazorpayPaymentId("pay_1")).thenReturn(Optional.empty());
        when(paymentClient.fetch("pay_1")).thenReturn(razorpayPayment("pay_1", 19900L, "INR", "captured", "order_1"));

        SubscriptionResponse response = svc.confirmCheckout(7, "pay_1", "sub_1", subscriptionSignature("pay_1", "sub_1"));

        assertEquals("ACTIVE", response.getStatus());
        assertEquals("PRO", response.getPlan());
        verify(paymentRepo).save(argThat(payment ->
                "pay_1".equals(payment.getRazorpayPaymentId())
                        && BigDecimal.valueOf(19900L, 2).equals(payment.getAmount())
                        && "CAPTURED".equals(payment.getStatus())));
        verify(kafkaTemplate).send(eq("user.subscription.status"), eq("7"), anyString());
    }

    @Test
    void confirmCheckout_terminalGatewayStatusReturnsDowngradedSubscription() throws Exception {
        Subscription pending = Subscription.builder()
                .id(71L)
                .userId(7)
                .plan("PRO")
                .status("PENDING")
                .razorpaySubId("sub_1")
                .build();
        when(subscriptionRepo.findByUserId(7)).thenReturn(Optional.of(pending));
        when(subscriptionClient.fetch("sub_1")).thenReturn(razorpaySubscription("sub_1", "cancelled", 0));

        SubscriptionResponse response = svc.confirmCheckout(7, "pay_1", "sub_1", subscriptionSignature("pay_1", "sub_1"));

        assertEquals("CANCELLED", response.getStatus());
        assertEquals("FREE", response.getPlan());
        verify(paymentRepo, never()).save(any());
        verify(kafkaTemplate, never()).send(eq("user.subscription.status"), anyString(), anyString());
    }

    @Test
    void confirmCheckout_rejectsSubscriptionOwnedByAnotherUser() {
        Subscription pending = Subscription.builder()
                .userId(7)
                .razorpaySubId("sub_owned")
                .build();
        when(subscriptionRepo.findByUserId(7)).thenReturn(Optional.of(pending));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> svc.confirmCheckout(7, "pay_1", "sub_other", "bad"));

        assertEquals("Subscription id does not belong to the authenticated user", ex.getMessage());
    }

    @Test
    void confirmCheckout_rejectsInvalidSignature() {
        Subscription pending = Subscription.builder()
                .userId(7)
                .razorpaySubId("sub_1")
                .build();
        when(subscriptionRepo.findByUserId(7)).thenReturn(Optional.of(pending));

        assertThrows(PaymentGatewayException.class,
                () -> svc.confirmCheckout(7, "pay_1", "sub_1", "bad-signature"));
    }

    @Test
    void createSubscription_existingActive_returnsExisting() {
        Subscription existing = Subscription.builder().userId(1).status("ACTIVE").plan("PRO").razorpaySubId("sub_1").build();
        when(subscriptionRepo.findByUserId(1)).thenReturn(Optional.of(existing));

        SubscriptionResponse res = svc.createSubscription(1, "plan_1", 12, "test@test.com");

        assertEquals("sub_1", res.getRazorpaySubId());
        verify(subscriptionRepo, never()).save(any());
    }

    @Test
    void createSubscription_existingFreshPending_returnsExisting() throws Exception {
        Subscription existing = Subscription.builder()
                .id(20L)
                .userId(2)
                .status("PENDING")
                .plan("PRO")
                .razorpaySubId("sub_pending")
                .startDate(LocalDateTime.now().minusMinutes(5))
                .build();
        when(subscriptionRepo.findByUserId(2)).thenReturn(Optional.of(existing));
        when(paymentRepo.findBySubscriptionIdOrderByCreatedAtDesc(20L)).thenReturn(List.of());
        when(subscriptionClient.fetch("sub_pending")).thenReturn(razorpaySubscription("sub_pending", "created", 0));

        SubscriptionResponse res = svc.createSubscription(2, "plan_1", 12, "test@test.com");

        assertEquals("PENDING", res.getStatus());
        assertEquals("sub_pending", res.getRazorpaySubId());
        verify(subscriptionClient, never()).create(any(JSONObject.class));
    }

    @Test
    void createSubscription_existingStalePending_expiresAndCreatesFreshCheckout() throws Exception {
        Subscription existing = Subscription.builder()
                .id(30L)
                .userId(3)
                .status("PENDING")
                .plan("PRO")
                .razorpaySubId("sub_old")
                .startDate(LocalDateTime.now().minusMinutes(31))
                .build();
        when(subscriptionRepo.findByUserId(3)).thenReturn(Optional.of(existing));
        when(paymentRepo.findBySubscriptionIdOrderByCreatedAtDesc(30L)).thenReturn(List.of());
        when(subscriptionClient.fetch("sub_old")).thenReturn(razorpaySubscription("sub_old", "created", 0));
        when(subscriptionClient.create(any(JSONObject.class))).thenReturn(razorpaySubscription("sub_new", "created", 0));

        SubscriptionResponse res = svc.createSubscription(3, "plan_1", 12, "test@test.com");

        assertEquals("PENDING", res.getStatus());
        assertEquals("PRO", res.getPlan());
        assertEquals("sub_new", res.getRazorpaySubId());
        verify(kafkaTemplate).send(eq("user.subscription.status"), eq("3"), anyString());
        verify(subscriptionRepo, atLeast(2)).save(existing);
    }

    @Test
    void createSubscription_blankPlanId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.createSubscription(3, " ", 12, "test@test.com"));
    }

    @Test
    void createSubscription_newUser_createsRazorpaySubscriptionAndLocalRow() throws Exception {
        when(subscriptionRepo.findByUserId(8)).thenReturn(Optional.empty());
        when(subscriptionClient.create(any(JSONObject.class))).thenReturn(razorpaySubscription("sub_new", "created", 0));

        SubscriptionResponse response = svc.createSubscription(8, "plan_pro", 12, "user@example.com");

        assertEquals("PENDING", response.getStatus());
        assertEquals("PRO", response.getPlan());
        assertEquals("sub_new", response.getRazorpaySubId());
        verify(subscriptionRepo).save(argThat(sub ->
                sub.getUserId() == 8
                        && "user@example.com".equals(sub.getUserEmail())
                        && "sub_new".equals(sub.getRazorpaySubId())));
    }

    @Test
    void createSubscription_gatewayFailureUsesFriendlyMessage() throws Exception {
        when(subscriptionRepo.findByUserId(8)).thenReturn(Optional.empty());
        when(subscriptionClient.create(any(JSONObject.class))).thenThrow(new RuntimeException("Unauthorized"));

        PaymentGatewayException ex = assertThrows(PaymentGatewayException.class,
                () -> svc.createSubscription(8, "plan_pro", 12, "user@example.com"));

        assertEquals("Razorpay rejected the payment credentials. Check RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET.",
                ex.getMessage());
    }

    @Test
    void getSubscription_pendingWithCapturedPayment_activates() {
        Subscription pending = Subscription.builder()
                .id(40L)
                .userId(4)
                .status("PENDING")
                .plan("PRO")
                .razorpaySubId("sub_paid")
                .startDate(LocalDateTime.now().minusMinutes(45))
                .build();
        Payment captured = Payment.builder().subscriptionId(40L).status("CAPTURED").build();
        when(subscriptionRepo.findByUserId(4)).thenReturn(Optional.of(pending));
        when(paymentRepo.findBySubscriptionIdOrderByCreatedAtDesc(40L)).thenReturn(List.of(captured));

        Optional<SubscriptionResponse> res = svc.getSubscription(4);

        assertTrue(res.isPresent());
        assertEquals("ACTIVE", res.get().getStatus());
        assertEquals("PRO", res.get().getPlan());
        verify(kafkaTemplate).send(eq("user.subscription.status"), eq("4"), anyString());
    }

    @Test
    void getSubscription_pendingWithGatewayPaidCycle_activates() throws Exception {
        Subscription pending = Subscription.builder()
                .id(50L)
                .userId(5)
                .status("PENDING")
                .plan("PRO")
                .razorpaySubId("sub_gateway_paid")
                .startDate(LocalDateTime.now().minusMinutes(45))
                .build();
        when(subscriptionRepo.findByUserId(5)).thenReturn(Optional.of(pending));
        when(paymentRepo.findBySubscriptionIdOrderByCreatedAtDesc(50L)).thenReturn(List.of());
        when(subscriptionClient.fetch("sub_gateway_paid"))
                .thenReturn(razorpaySubscription("sub_gateway_paid", "created", 1));

        Optional<SubscriptionResponse> res = svc.getSubscription(5);

        assertTrue(res.isPresent());
        assertEquals("ACTIVE", res.get().getStatus());
        assertEquals("PRO", res.get().getPlan());
        verify(kafkaTemplate).send(eq("user.subscription.status"), eq("5"), anyString());
    }

    @Test
    void handleWebhookEvent_activated_upgradesExisting() {
        JSONObject payload = new JSONObject("{\"subscription\":{\"entity\":{\"id\":\"sub_1\"}}}");
        Subscription existing = new Subscription();
        existing.setUserId(1);
        existing.setRazorpaySubId("sub_1");
        
        when(subscriptionRepo.findByRazorpaySubId("sub_1")).thenReturn(Optional.of(existing));

        svc.handleWebhookEvent("subscription.activated", payload);

        assertEquals("ACTIVE", existing.getStatus());
        assertEquals("PRO", existing.getPlan());
        verify(subscriptionRepo).save(existing);
        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("user.subscription.status"), eq("1"), eventCaptor.capture());
        JSONObject sentEvent = new JSONObject(eventCaptor.getValue());
        assertEquals(1, sentEvent.getInt("userId"));
        assertEquals("PRO", sentEvent.getString("status"));
    }

    @Test
    void handleWebhookEvent_activated_createsNewFromNotes() {
        JSONObject payload = new JSONObject("{\"subscription\":{\"entity\":{\"id\":\"sub_new\", \"notes\":{\"userId\":2}}}}");
        when(subscriptionRepo.findByRazorpaySubId("sub_new")).thenReturn(Optional.empty());
        when(subscriptionRepo.findByUserId(2)).thenReturn(Optional.empty());

        svc.handleWebhookEvent("subscription.activated", payload);

        verify(subscriptionRepo).save(argThat(sub -> sub.getUserId() == 2 && "PRO".equals(sub.getPlan())));
        verify(kafkaTemplate).send(eq("user.subscription.status"), eq("2"), anyString());
    }

    @Test
    void handleWebhookEvent_cancelled_cancelsExisting() {
        JSONObject payload = new JSONObject("{\"subscription\":{\"entity\":{\"id\":\"sub_1\"}}}");
        Subscription existing = new Subscription();
        existing.setUserId(1);
        existing.setRazorpaySubId("sub_1");
        
        when(subscriptionRepo.findByRazorpaySubId("sub_1")).thenReturn(Optional.of(existing));

        svc.handleWebhookEvent("subscription.cancelled", payload);

        assertEquals("CANCELLED", existing.getStatus());
        assertEquals("FREE", existing.getPlan());
        assertNotNull(existing.getEndDate());
        verify(subscriptionRepo).save(existing);
        verify(kafkaTemplate).send(eq("user.subscription.status"), eq("1"), anyString());
    }

    @Test
    void handleWebhookEvent_paymentCaptured_recordsPayment() {
        JSONObject payload = new JSONObject("{\"payment\":{\"entity\":{\"id\":\"pay_1\", \"amount\":10000, \"currency\":\"INR\", \"subscription_id\":\"sub_1\"}}}");
        Subscription existing = Subscription.builder().id(10L).razorpaySubId("sub_1").build();
        
        when(paymentRepo.findByRazorpayPaymentId("pay_1")).thenReturn(Optional.empty());
        when(subscriptionRepo.findByRazorpaySubId("sub_1")).thenReturn(Optional.of(existing));

        svc.handleWebhookEvent("payment.captured", payload);

        verify(paymentRepo).save(argThat(p -> 
            "pay_1".equals(p.getRazorpayPaymentId()) && 
            "CAPTURED".equals(p.getStatus()) &&
            10L == p.getSubscriptionId()
        ));
    }

    @Test
    void cancelUserSubscription_alreadyFree_returnsExistingWithoutGatewayCall() throws Exception {
        Subscription existing = Subscription.builder().userId(1).status("ACTIVE").plan("FREE").build();
        when(subscriptionRepo.findByUserId(1)).thenReturn(Optional.of(existing));

        SubscriptionResponse response = svc.cancelUserSubscription(1, null);

        assertEquals("FREE", response.getPlan());
        verify(subscriptionClient, never()).cancel(anyString(), any(JSONObject.class));
    }

    @Test
    void cancelUserSubscription_wrongSubscriptionId_throws() {
        Subscription existing = Subscription.builder()
                .userId(1)
                .status("ACTIVE")
                .plan("PRO")
                .razorpaySubId("sub_owned")
                .build();
        when(subscriptionRepo.findByUserId(1)).thenReturn(Optional.of(existing));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> svc.cancelUserSubscription(1, "sub_other"));

        assertEquals("Subscription id does not belong to the authenticated user", ex.getMessage());
    }

    @Test
    void cancelUserSubscription_successCancelsAndPublishesEvent() throws Exception {
        Subscription existing = Subscription.builder()
                .userId(1)
                .status("ACTIVE")
                .plan("PRO")
                .razorpaySubId("sub_owned")
                .build();
        when(subscriptionRepo.findByUserId(1)).thenReturn(Optional.of(existing));

        SubscriptionResponse response = svc.cancelUserSubscription(1, "sub_owned");

        assertEquals("CANCELLED", response.getStatus());
        assertEquals("FREE", response.getPlan());
        assertNotNull(existing.getEndDate());
        verify(subscriptionClient).cancel(eq("sub_owned"), any(JSONObject.class));
        verify(kafkaTemplate).send(eq("user.subscription.status"), eq("1"), anyString());
    }

    @Test
    void cancelUserSubscription_gatewayFailureIsWrapped() throws Exception {
        Subscription existing = Subscription.builder()
                .userId(1)
                .status("ACTIVE")
                .plan("PRO")
                .razorpaySubId("sub_owned")
                .build();
        when(subscriptionRepo.findByUserId(1)).thenReturn(Optional.of(existing));
        when(subscriptionClient.cancel(eq("sub_owned"), any(JSONObject.class))).thenThrow(new RuntimeException("plan rejected"));

        PaymentGatewayException ex = assertThrows(PaymentGatewayException.class,
                () -> svc.cancelUserSubscription(1, "sub_owned"));

        assertEquals("Razorpay rejected the selected plan. Check RAZORPAY_PRO_PLAN_ID.", ex.getMessage());
    }

    @Test
    void handleWebhookEvent_completedExpiresExistingSubscription() {
        JSONObject payload = new JSONObject("{\"subscription\":{\"entity\":{\"id\":\"sub_1\"}}}");
        Subscription existing = Subscription.builder().userId(1).status("ACTIVE").plan("PRO").razorpaySubId("sub_1").build();
        when(subscriptionRepo.findByRazorpaySubId("sub_1")).thenReturn(Optional.of(existing));

        svc.handleWebhookEvent("subscription.completed", payload);

        assertEquals("EXPIRED", existing.getStatus());
        assertEquals("FREE", existing.getPlan());
        verify(kafkaTemplate).send(eq("user.subscription.status"), eq("1"), anyString());
    }

    @Test
    void handleWebhookEvent_paymentCapturedQueuesReceiptEmail() {
        JSONObject payload = new JSONObject("{\"payment\":{\"entity\":{\"id\":\"pay_2\", \"amount\":19900, \"currency\":\"INR\", \"subscription_id\":\"sub_1\"}}}");
        Subscription existing = Subscription.builder()
                .id(10L)
                .userId(1)
                .userEmail("user@example.com")
                .plan("PRO")
                .razorpaySubId("sub_1")
                .build();
        when(paymentRepo.findByRazorpayPaymentId("pay_2")).thenReturn(Optional.empty());
        when(subscriptionRepo.findByRazorpaySubId("sub_1")).thenReturn(Optional.of(existing));
        when(subscriptionRepo.findById(10L)).thenReturn(Optional.of(existing));

        svc.handleWebhookEvent("payment.captured", payload);

        verify(redis).convertAndSend(eq("email:send"), contains("subscription_confirmation"));
    }

    @Test
    void handleWebhookEvent_paymentFailedWithoutSubscriptionIdSkipsRecord() {
        JSONObject payload = new JSONObject("{\"payment\":{\"entity\":{\"id\":\"pay_missing\", \"amount\":19900, \"currency\":\"INR\"}}}");
        when(paymentRepo.findByRazorpayPaymentId("pay_missing")).thenReturn(Optional.empty());

        svc.handleWebhookEvent("payment.failed", payload);

        verify(paymentRepo, never()).save(any(Payment.class));
    }

    @Test
    void getSubscription_gatewayCompletedPendingSubscriptionExpiresIt() throws Exception {
        Subscription pending = Subscription.builder()
                .id(90L)
                .userId(9)
                .status("PENDING")
                .plan("PRO")
                .razorpaySubId("sub_done")
                .startDate(LocalDateTime.now().minusMinutes(10))
                .build();
        when(subscriptionRepo.findByUserId(9)).thenReturn(Optional.of(pending));
        when(paymentRepo.findBySubscriptionIdOrderByCreatedAtDesc(90L)).thenReturn(List.of());
        when(subscriptionClient.fetch("sub_done")).thenReturn(razorpaySubscription("sub_done", "completed", 0));

        Optional<SubscriptionResponse> response = svc.getSubscription(9);

        assertTrue(response.isPresent());
        assertEquals("EXPIRED", response.get().getStatus());
        assertEquals("FREE", response.get().getPlan());
        verify(kafkaTemplate).send(eq("user.subscription.status"), eq("9"), anyString());
    }

    @Test
    void getPaymentHistory_withoutSubscriptionReturnsEmptyList() {
        when(subscriptionRepo.findByUserId(99)).thenReturn(Optional.empty());

        assertTrue(svc.getPaymentHistory(99).isEmpty());
    }

    private com.razorpay.Subscription razorpaySubscription(String id, String status, int paidCount) {
        return new com.razorpay.Subscription(new JSONObject()
                .put("id", id)
                .put("status", status)
                .put("paid_count", paidCount));
    }

    private com.razorpay.Payment razorpayPayment(String id, long amount, String currency, String status, String orderId) {
        return new com.razorpay.Payment(new JSONObject()
                .put("id", id)
                .put("amount", amount)
                .put("currency", currency)
                .put("status", status)
                .put("order_id", orderId));
    }

    private String subscriptionSignature(String paymentId, String subscriptionId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((paymentId + "|" + subscriptionId).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
