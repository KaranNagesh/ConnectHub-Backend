package com.connecthub.payment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ConfirmSubscriptionRequest {
    @NotBlank(message = "razorpayPaymentId is required")
    private String razorpayPaymentId;

    @NotBlank(message = "razorpaySubscriptionId is required")
    private String razorpaySubscriptionId;

    @NotBlank(message = "razorpaySignature is required")
    private String razorpaySignature;
}
