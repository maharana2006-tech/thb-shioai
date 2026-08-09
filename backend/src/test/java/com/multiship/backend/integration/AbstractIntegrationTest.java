package com.multiship.backend.integration;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Sprint 49 Tier 5 Fix 1 — base class for real-Postgres integration
 * tests.
 *
 * <p>Every subclass gets a live Postgres container (shared across all
 * tests thanks to {@code static @Container}) and a fully-wired Spring
 * Boot context. Subclasses {@code @Autowired} the services they want
 * to exercise — repositories, controllers-as-methods, etc. Real DB,
 * real transactions, real events.
 *
 * <p>{@code JWT_SECRET} + {@code SECRETS_ENCRYPTION_KEY} are injected
 * as throwaway values so the Tier 0 / Tier 1 fail-fasts don't block
 * context load.
 *
 * <p>Guarded by {@code INTEGRATION_TESTS=1} so CI without Docker
 * still runs the full unit suite green. To enable locally:
 * <pre>
 * INTEGRATION_TESTS=1 mvn test
 * </pre>
 *
 * <p>Spring Boot 4 restructured its test-web surface (moved
 * {@code @AutoConfigureMockMvc} + removed {@code TestRestTemplate}
 * from spring-boot-test). Subclasses that need HTTP-level testing
 * can hit the RANDOM_PORT with plain {@code java.net.http.HttpClient}
 * against {@code https://localhost:${server.port}/...} — inject the
 * port via {@code @LocalServerPort}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "1")
@TestPropertySource(properties = {
        "jwt.secret=integration-test-jwt-secret-do-not-use-in-prod-32b",
        // 32-byte base64 for SECRETS_ENCRYPTION_KEY (all zeros — test only).
        "secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
public abstract class AbstractIntegrationTest {

    @Container
    protected static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("multiship_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withReuse(true);

    @DynamicPropertySource
    static void wireDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // With a fresh container we can safely let Hibernate own the schema
        // for tests — no Flyway baseline required.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
    }
}
