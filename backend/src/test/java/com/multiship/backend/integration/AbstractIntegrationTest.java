package com.multiship.backend.integration;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

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
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "1")
@TestPropertySource(properties = {
        "jwt.secret=integration-test-jwt-secret-do-not-use-in-prod-32b",
        // 32-byte base64 for SECRETS_ENCRYPTION_KEY (all zeros — test only).
        "secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        // Sprint 50 Tier 1-C: Redis autoconfig hard-fails on the empty default
        // host. Integration tests that don't need Redis stay clean by excluding
        // it here. Subclasses that DO need Redis (IdempotencyIntegrationTest)
        // override this to an empty string via their own @TestPropertySource
        // so autoconfig runs against the container-provided host/port.
        "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration",
        // Sprint 50 Tier 1-C: prod pool size (50) × N cached test contexts
        // overwhelms Testcontainers Postgres' default max_connections (100)
        // and integration tests start failing with "too many clients already."
        // 5 is comfortable for a single-test suite while still letting the
        // pool exercise its own logic.
        "spring.datasource.hikari.maximum-pool-size=5",
})
public abstract class AbstractIntegrationTest {

    /**
     * JVM-singleton container. Deliberately NOT {@code @Container} —
     * that annotation ties lifecycle to a single test class, so Spring's
     * cached context (URL captured for class 1's port) breaks when class
     * 2 boots against a new port. Starting the container in a static
     * initializer + never stopping it (JVM exit handles cleanup) keeps
     * the port stable for every class in the suite.
     */
    protected static final PostgreSQLContainer<?> postgres;
    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("multiship_test")
                .withUsername("test")
                .withPassword("test");
        postgres.start();
    }

    @DynamicPropertySource
    static void wireDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // `update` (not create-drop): the Testcontainers Postgres is shared
        // across all @SpringBootTest classes in the suite, and create-drop
        // wipes the schema between contexts, breaking any test that runs
        // after the first. update keeps the schema across the shared
        // container's lifetime; Hibernate re-runs the create for a fresh
        // container on the next JVM.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.flyway.enabled", () -> "false");
    }
}
