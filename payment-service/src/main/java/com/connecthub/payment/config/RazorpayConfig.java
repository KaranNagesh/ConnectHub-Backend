package com.connecthub.payment.config;

import com.razorpay.RazorpayClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Razorpay SDK bean — uses hyphen-notation properties consistent with application.yml.
 * key-id and key-secret are bound via Spring's relaxed binding from:
 *   razorpay.key-id / razorpay.key-secret
 */
@Configuration
public class RazorpayConfig {

    @Value("${razorpay.key-id}")
    private String keyId;

    @Value("${razorpay.key-secret}")
    private String keySecret;

    @Bean
    public RazorpayClient razorpayClient() throws Exception {
        return new RazorpayClient(keyId, keySecret);
    }
}
