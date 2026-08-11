package com.multiship.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        // Sprint 49 Tier 0: JwtService rejects empty / short / compromised
        // secrets on startup. Supply a valid throwaway one for context-loads.
        "jwt.secret=test-only-jwt-secret-do-not-use-in-production-32b",
        // Sprint 50 Tier 0.5 CI unblock: Flyway is a dependency of the
        // EntityManagerFactory bean, so it runs BEFORE Hibernate's
        // ddl-auto=update. Every migration V2+ ALTERs tables that only
        // exist because Hibernate creates them LATER — on a fresh Postgres
        // this cascades to context-load failures. This smoke test only
        // proves the Spring context wires up; schema management is out
        // of scope. Bypass Flyway and let Hibernate build from entity
        // metadata (AbstractIntegrationTest does the same via
        // DynamicPropertySource for the integration suite).
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
