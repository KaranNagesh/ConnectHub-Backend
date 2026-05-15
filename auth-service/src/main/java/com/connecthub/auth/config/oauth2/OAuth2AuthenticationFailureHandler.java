package com.connecthub.auth.config.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class OAuth2AuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    @Value("${oauth2.redirect.frontend-url:http://localhost:4200}")
    private String frontendUrl;

    private static final String FRONTEND_CALLBACK_PATH = "/auth/oauth2/callback";

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                         AuthenticationException exception) throws IOException {
        log.error("OAuth2 authentication failed: {}", exception.getMessage());

        String redirectUrl = UriComponentsBuilder.fromUriString(frontendUrl + FRONTEND_CALLBACK_PATH)
                .queryParam("error", URLEncoder.encode(
                        exception.getLocalizedMessage() != null
                            ? exception.getLocalizedMessage()
                            : "OAuth2 authentication failed",
                        StandardCharsets.UTF_8))
                .build().toUriString();

        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }
}
