package com.connecthub.auth.config;

import com.connecthub.auth.entity.User;
import com.connecthub.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the platform admin account on first startup.
 *
 * The admin credentials are configurable via environment variables:
 *   PLATFORM_ADMIN_EMAIL    (default: admin@connecthub.app)
 *   PLATFORM_ADMIN_PASSWORD (default: Admin@ConnectHub2024!)
 *   PLATFORM_ADMIN_USERNAME (default: admin)
 *
 * If the admin account already exists, this runner is a no-op.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${platform.admin.email:admin@connecthub.app}")
    private String adminEmail;

    @Value("${platform.admin.password:Admin@ConnectHub2024!}")
    private String adminPassword;

    @Value("${platform.admin.username:admin}")
    private String adminUsername;

    @Override
    public void run(String... args) {
        if (userRepository.findByEmail(adminEmail).isPresent()) {
            log.info("Platform admin already exists ({})", adminEmail);
            return;
        }
        if (userRepository.findByUsername(adminUsername).isPresent()) {
            log.info("Platform admin username '{}' already taken — skipping seed", adminUsername);
            return;
        }

        User admin = User.builder()
                .username(adminUsername)
                .email(adminEmail)
                .passwordHash(passwordEncoder.encode(adminPassword))
                .fullName("Platform Admin")
                .role("ADMIN")
                .emailVerified(true)
                .active(true)
                .status("ONLINE")
                .provider("LOCAL")
                .subscriptionTier("PRO")
                .build();

        userRepository.save(admin);
        log.info("✅ Platform admin seeded: {} ({})", adminUsername, adminEmail);
        log.warn("⚠️  Change the default admin password via PLATFORM_ADMIN_PASSWORD env var!");
    }
}
