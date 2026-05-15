package com.connecthub.payment.controller;

import com.connecthub.payment.dto.ConfirmSubscriptionRequest;
import com.connecthub.payment.dto.CreateSubscriptionRequest;
import com.connecthub.payment.dto.CheckoutConfigResponse;
import com.connecthub.payment.dto.PaymentResponse;
import com.connecthub.payment.dto.PlanResponse;
import com.connecthub.payment.dto.SubscriptionResponse;
import com.connecthub.payment.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionControllerTest {

    @Mock
    private SubscriptionService subscriptionService;

    @InjectMocks
    private SubscriptionController subscriptionController;

    @Test
    void getCheckoutConfig_returnsConfiguredKeyAndPlans() {
        ReflectionTestUtils.setField(subscriptionController, "razorpayKeyId", "rzp_test_key");
        ReflectionTestUtils.setField(subscriptionController, "proPlanId", "plan_pro");
        ReflectionTestUtils.setField(subscriptionController, "proPrice", BigDecimal.valueOf(199));

        ResponseEntity<CheckoutConfigResponse> res = subscriptionController.getCheckoutConfig();

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("rzp_test_key", res.getBody().getRazorpayKeyId());
        assertEquals(2, res.getBody().getPlans().size());
        assertEquals("plan_pro", res.getBody().getPlans().get(1).getPlanId());
    }

    @Test
    void getPlans_returnsFreeAndProPlans() {
        ReflectionTestUtils.setField(subscriptionController, "proPlanId", "plan_pro");
        ReflectionTestUtils.setField(subscriptionController, "proPrice", BigDecimal.valueOf(199));

        ResponseEntity<List<PlanResponse>> res = subscriptionController.getPlans();

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("Free", res.getBody().get(0).getName());
        assertEquals("Pro", res.getBody().get(1).getName());
    }

    @Test
    void createSubscription() {
        CreateSubscriptionRequest req = new CreateSubscriptionRequest();
        req.setPlanId("plan_1");
        req.setTotalCount(12);
        
        SubscriptionResponse sr = SubscriptionResponse.builder().build();
        when(subscriptionService.createSubscription(1, "plan_1", 12, null)).thenReturn(sr);

        ResponseEntity<SubscriptionResponse> res = subscriptionController.createSubscription(1, null, req);
        
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(sr, res.getBody());
    }

    @Test
    void createSubscription_passesOptionalUserEmail() {
        CreateSubscriptionRequest req = new CreateSubscriptionRequest();
        req.setPlanId("plan_1");
        req.setTotalCount(12);
        SubscriptionResponse sr = SubscriptionResponse.builder().build();
        when(subscriptionService.createSubscription(1, "plan_1", 12, "user@example.com")).thenReturn(sr);

        ResponseEntity<SubscriptionResponse> res = subscriptionController.createSubscription(1, "user@example.com", req);

        assertEquals(sr, res.getBody());
    }

    @Test
    void confirmSubscription_delegatesToService() {
        ConfirmSubscriptionRequest req = new ConfirmSubscriptionRequest();
        req.setRazorpayPaymentId("pay_1");
        req.setRazorpaySubscriptionId("sub_1");
        req.setRazorpaySignature("sig");
        SubscriptionResponse sr = SubscriptionResponse.builder().status("ACTIVE").build();
        when(subscriptionService.confirmCheckout(1, "pay_1", "sub_1", "sig")).thenReturn(sr);

        ResponseEntity<SubscriptionResponse> res = subscriptionController.confirmSubscription(1, req);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(sr, res.getBody());
    }

    @Test
    void cancelSubscription_acceptsNullRequest() {
        SubscriptionResponse sr = SubscriptionResponse.builder().status("CANCELLED").build();
        when(subscriptionService.cancelUserSubscription(1, null)).thenReturn(sr);

        ResponseEntity<SubscriptionResponse> res = subscriptionController.cancelSubscription(1, null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(sr, res.getBody());
    }

    @Test
    void cancelSubscription_usesRequestSubscriptionId() {
        SubscriptionResponse sr = SubscriptionResponse.builder().status("CANCELLED").build();
        when(subscriptionService.cancelUserSubscription(1, "sub_1")).thenReturn(sr);

        ResponseEntity<SubscriptionResponse> res = subscriptionController.cancelSubscription(1, Map.of("razorpaySubId", "sub_1"));

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(sr, res.getBody());
    }

    @Test
    void getStatus_found() {
        SubscriptionResponse sr = SubscriptionResponse.builder().build();
        when(subscriptionService.getSubscription(1)).thenReturn(Optional.of(sr));

        ResponseEntity<SubscriptionResponse> res = subscriptionController.getStatus(1);
        
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(sr, res.getBody());
    }

    @Test
    void getStatus_notFoundReturnsFreeSubscription() {
        when(subscriptionService.getSubscription(1)).thenReturn(Optional.empty());

        ResponseEntity<SubscriptionResponse> res = subscriptionController.getStatus(1);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        SubscriptionResponse body = res.getBody();
        assertNotNull(body);
        assertEquals(1, body.getUserId());
        assertEquals("FREE", body.getPlan());
        assertEquals("ACTIVE", body.getStatus());
    }

    @Test
    void getPaymentHistory() {
        List<PaymentResponse> history = List.of(PaymentResponse.builder().build());
        when(subscriptionService.getPaymentHistory(1)).thenReturn(history);

        ResponseEntity<List<PaymentResponse>> res = subscriptionController.getPaymentHistory(1);
        
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(history, res.getBody());
    }
}
