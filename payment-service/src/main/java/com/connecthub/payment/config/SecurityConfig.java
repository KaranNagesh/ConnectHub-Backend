package com.connecthub.payment.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Payment service security:
 *  - Webhook endpoint is public (Razorpay cannot send JWT)
 *  - All other /api/v1/payments/** require X-User-Id header (injected by gateway after JWT validation)
 *  - Stateless (no session)
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtUtil jwtUtil;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/payments/**", "/actuator/**", "/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html"))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Webhook is public — signature is verified internally by WebhookController
                .requestMatchers("/api/v1/payments/webhook").permitAll()
                // Actuator health is always public
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                // Swagger
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // Everything else requires authentication via gateway-injected header
                .anyRequest().authenticated()
            )
            .addFilterBefore(new JwtHeaderFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
