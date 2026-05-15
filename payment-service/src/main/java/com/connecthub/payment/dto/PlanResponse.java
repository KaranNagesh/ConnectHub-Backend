package com.connecthub.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanResponse {
    private String planId;
    private String name;
    private BigDecimal price;
    private String currency;
    private String interval;
    private String description;
    private List<String> features;
    private boolean recommended;
}
