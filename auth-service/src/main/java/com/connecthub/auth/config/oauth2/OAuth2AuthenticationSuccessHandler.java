package com.connecthub.auth.config.oauth2;

import com.connecthub.auth.config.JwtUtil;
import com.connecthub.auth.entity.User;
import com.connecthub.auth.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    @Value("${oauth2.redirect.frontend-url:http://localhost:4200}")
    private String frontendUrl;

    private static final String FRONTEND_CALLBACK_PATH = "/auth/oauth2/callback";

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {

        if (response.isCommitted()) {
            log.warn("Response already committed");
            return;
        }

        OAuth2AuthenticationToken authToken = (OAuth2AuthenticationToken) authentication;
        OAuth2User oAuth2User = authToken.getPrincipal();
        String provider = authToken.getAuthorizedClientRegistrationId().toUpperCase();

        // Extract user details from OAuth2 provider
        String email = extractEmail(oAuth2User, provider);
        String name = extractName(oAuth2User, provider);
        String picture = extractPicture(oAuth2User, provider);
        String providerId = extractProviderId(oAuth2User, provider);

        if (email == null || email.isBlank()) {
            log.error("OAuth2 login failed: no email from provider {}", provider);
            String errorUrl = UriComponentsBuilder.fromUriString(frontendUrl + FRONTEND_CALLBACK_PATH)
                    .queryParam("error", "No email received from " + provider)
                    .build().toUriString();
            getRedirectStrategy().sendRedirect(request, response, errorUrl);
            return;
        }

        // Find existing user or create new one
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            // Auto-register — email already verified by Google/GitHub
            user = User.builder()
                    .username(generateUsername(email))
                    .email(email)
                    .fullName(name)
                    .avatarUrl(picture)
                    .provider(provider)
                    .providerId(providerId)
                    .emailVerified(true)
                    .active(true)
                    .role("USER")
                    .build();
            user = userRepository.save(user);
            log.info("OAuth2 new user created: {} via {}", user.getUsername(), provider);
        } else {
            // Existing user — update provider info and avatar if needed
            if ("LOCAL".equals(user.getProvider())) {
                user.setProvider(provider);
                user.setProviderId(providerId);
            }
            if (picture != null && (user.getAvatarUrl() == null || user.getAvatarUrl().isBlank())) {
                user.setAvatarUrl(picture);
            }
            if (name != null && (user.getFullName() == null || user.getFullName().isBlank())) {
                user.setFullName(name);
            }
            userRepository.save(user);
            log.info("OAuth2 existing user logged in: {} via {}", user.getUsername(), provider);
        }

        if (!user.isActive()) {
            String errorUrl = UriComponentsBuilder.fromUriString(frontendUrl + FRONTEND_CALLBACK_PATH)
                    .queryParam("error", "Account is suspended")
                    .build().toUriString();
            getRedirectStrategy().sendRedirect(request, response, errorUrl);
            return;
        }

        // Generate JWT tokens
        String accessToken = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user);

        log.info("OAuth2 success — redirecting user {} to frontend", user.getUserId());


        // Redirect to frontend with tokens in URL
        // Best practice: short-lived token in URL → frontend reads it immediately and stores
        String redirectUrl = UriComponentsBuilder.fromUriString(frontendUrl + FRONTEND_CALLBACK_PATH)
                .queryParam("token", accessToken)
                .queryParam("refreshToken", refreshToken)
                .queryParam("userId", user.getUserId())
                .queryParam("username", user.getUsername())
                .queryParam("email", user.getEmail())
                .build()
                .toUriString();
        response.sendRedirect(redirectUrl);

    }

    private String extractEmail(OAuth2User user, String provider) {
        return switch (provider) {
            case "GOOGLE" -> user.getAttribute("email");
            case "GITHUB" -> {
                String email = user.getAttribute("email");
                // GitHub email might be null if private — would need separate API call
                yield email;
            }
            default -> user.getAttribute("email");
        };
    }

    private String extractName(OAuth2User user, String provider) {
        return switch (provider) {
            case "GOOGLE" -> user.getAttribute("name");
            case "GITHUB" -> {
                String name = user.getAttribute("name");
                yield name != null ? name : user.getAttribute("login");
            }
            default -> user.getAttribute("name");
        };
    }

    private String extractPicture(OAuth2User user, String provider) {
        return switch (provider) {
            case "GOOGLE" -> user.getAttribute("picture");
            case "GITHUB" -> user.getAttribute("avatar_url");
            default -> null;
        };
    }

    private String extractProviderId(OAuth2User user, String provider) {
        Object id = user.getAttribute("id");
        if (id == null) id = user.getAttribute("sub"); // Google uses "sub"
        return id != null ? String.valueOf(id) : null;
    }

    private String generateUsername(String email) {
        String base = email.split("@")[0].replaceAll("[^a-zA-Z0-9_]", "_");
        if (base.length() > 25) base = base.substring(0, 25);
        if (userRepository.existsByUsername(base)) {
            base = base + "_" + (System.currentTimeMillis() % 10000);
        }
        return base;
    }
}
