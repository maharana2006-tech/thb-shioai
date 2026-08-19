package com.multiship.backend;

import com.multiship.backend.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Spring context-load smoke test.
 *
 * <p><b>Sprint 51 — DB-safety fix.</b> This test now extends
 * {@link AbstractIntegrationTest} so it boots against a throwaway
 * Testcontainers Postgres, NEVER the configured application datasource.
 *
 * <p>Previously it was a bare {@code @SpringBootTest} with
 * {@code spring.jpa.hibernate.ddl-auto=create-drop} and <em>no</em>
 * datasource override. With no Docker available it fell through to the
 * real datasource from {@code application.properties}, so running
 * {@code mvn test} locally dropped and recreated every table in the live
 * dev database ({@code multiship_db}) — silently wiping all data. Making it
 * a Testcontainers test removes that footgun entirely: {@code create-drop}
 * (now {@code update} on a fresh container, inherited from the base) can
 * only ever touch the disposable container.
 *
 * <p>Inheriting the base also inherits its
 * {@code @EnabledIfEnvironmentVariable(INTEGRATION_TESTS=1)} guard, so a
 * plain {@code mvn test} without Docker skips this cleanly instead of
 * destroying a real database. Run it with {@code INTEGRATION_TESTS=1 mvn test}
 * (Docker required), which is also how CI runs the integration suite.
 */
class BackendApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }
}
