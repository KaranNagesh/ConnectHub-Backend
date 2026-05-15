package com.connecthub.admin.config;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

/**
 * Secures the Spring Boot Admin dashboard with HTTP Basic Auth + CSRF support.
 * All Admin UI routes require authentication. Actuator client registration
 * via /instances is also permitted for registered clients.
 */
@Configuration
@EnableWebSecurity
public class AdminSecurityConfig {

    private final AdminServerProperties adminServer;

    public AdminSecurityConfig(AdminServerProperties adminServer) {
        this.adminServer = adminServer;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        String adminPath = adminServer.path("/");

        SavedRequestAwareAuthenticationSuccessHandler successHandler =
                new SavedRequestAwareAuthenticationSuccessHandler();
        successHandler.setTargetUrlParameter("redirectTo");
        successHandler.setDefaultTargetUrl(adminPath);

        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    adminServer.path("/assets/**"),
                    adminServer.path("/login"),
                    adminServer.path("/actuator/**")
                ).permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage(adminServer.path("/login"))
                .successHandler(successHandler)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl(adminServer.path("/logout"))
                .permitAll()
            )
            .httpBasic(basic -> {})
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                    adminServer.path("/instances/**"),
                    adminServer.path("/actuator/**")
            ));

        return http.build();
    }
}
