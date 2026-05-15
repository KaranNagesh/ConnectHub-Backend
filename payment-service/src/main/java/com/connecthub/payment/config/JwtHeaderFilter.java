package com.connecthub.payment.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;

/**
 * Filter that reads the X-User-Id header (injected by api-gateway after JWT validation)
 * and sets a Spring Security authentication object, allowing @PreAuthorize and SecurityContext
 * to work correctly inside payment-service.
 *
 * Within Docker the gateway always injects this header; outside Docker a direct
 * Authorization: Bearer {token} is also accepted as fallback for integration tests.
 */
@RequiredArgsConstructor
@Slf4j
public class JwtHeaderFilter implements Filter {

    private final JwtUtil jwtUtil;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;

        // Primary path: gateway has already validated JWT and injected userId
        String userId = req.getHeader("X-User-Id");
        if (userId != null && !userId.isBlank()) {
            setAuth(userId);
            chain.doFilter(request, response);
            return;
        }

        // Fallback path: direct call with Bearer token (integration tests / local dev)
        String authHeader = req.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtUtil.isValid(token)) {
                setAuth(String.valueOf(jwtUtil.getUserId(token)));
            }
        }

        chain.doFilter(request, response);
    }

    private void setAuth(String userId) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                        userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
