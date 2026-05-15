package com.connecthub.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateSubscriptionRequest {
    /** Razorpay Plan ID to subscribe to (PRO or BUSINESS). */
    @NotBlank(message = "planId is required")
    private String planId;

    /** userId from the JWT; service resolves from X-User-Id header, so UI can omit. */
    private Integer userId;

    /** Number of billing cycles (default 12 = 1 year). */
    @Min(value = 1, message = "totalCount must be at least 1")
    private int totalCount = 12;
}
