package com.multiship.backend.config;

import com.multiship.backend.model.User;
import com.multiship.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Dev/first-boot convenience — creates one ADMIN user if the users table is
 * completely empty (fresh database, no accounts to log in with yet). Never
 * touches an existing install: any pre-existing user, admin or otherwise,
 * skips this entirely.
 *
 * Credentials are overridable via ADMIN_SEED_USERNAME / ADMIN_SEED_EMAIL /
 * ADMIN_SEED_PASSWORD; the defaults are for local dev only and are logged at
 * WARN on creation so they're never a silent surprise.
 *
 * Prod safety (Vuln 1 — hardcoded-default-admin): when the 'prod' profile is
 * active, ADMIN_SEED_PASSWORD is mandatory. Boot fails fast if it's unset —
 * mirrors the JWT_SECRET / SECRETS_ENCRYPTION_KEY contract in
 * application-prod.properties. Dev/CI keep the Admin@123 convenience.
 */
@Component
@RequiredArgsConstructor
@Order(100)
public class AdminUserSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminUserSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    @Override
    public void run(String... args) {
        if (users.count() > 0) {
            return;
        }

        String username = System.getenv().getOrDefault("ADMIN_SEED_USERNAME", "admin");
        String email = System.getenv().getOrDefault("ADMIN_SEED_EMAIL", "admin@multiship.local");
        String password = System.getenv().get("ADMIN_SEED_PASSWORD");
        if (password == null || password.isBlank()) {
            for (String p : environment.getActiveProfiles()) {
                if ("prod".equalsIgnoreCase(p)) {
                    throw new IllegalStateException(
                            "ADMIN_SEED_PASSWORD must be set on first boot in the 'prod' profile. "
                                    + "Refusing to seed the initial admin with a well-known default.");
                }
            }
            password = "Admin@123";  // dev / CI only — never reached in prod
        }

        User admin = User.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(password))
                .fullName("Administrator")
                .role("ADMIN")
                .emailVerified(true)
                .build();
        users.save(admin);

        log.warn("Seeded initial ADMIN user (username='{}', email='{}') with a default password — "
                + "log in and change it immediately. Override via ADMIN_SEED_USERNAME / "
                + "ADMIN_SEED_EMAIL / ADMIN_SEED_PASSWORD env vars on future first boots.",
                username, email);
    }
}
