package com.connecthub.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data @Builder
public class SubscriptionResponse {
    private Long     id;
    private Integer  userId;
    private String   plan;
    private String   status;
    private String   razorpaySubId;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private LocalDateTime createdAt;
}
