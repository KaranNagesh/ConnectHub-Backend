package com.connecthub.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @Builder
public class PaymentResponse {
    private Long      id;
    private Long      subscriptionId;
    private String    razorpayPaymentId;
    private String    razorpayOrderId;
    private BigDecimal amount;
    private String    currency;
    private String    status;
    private LocalDateTime createdAt;
}
