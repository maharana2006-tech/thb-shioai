package com.multiship.backend.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Sprint 50 PR I — guard rail for the DO-block fresh-DB pattern documented
 * in {@code docs/flyway-fresh-db-guard-pattern.md}.
 *
 * <p>Every NEW migration in {@code backend/src/main/resources/db/migration}
 * must be safe to apply against an empty Postgres — Spring Boot runs
 * Flyway BEFORE Hibernate creates the entity-metadata tables, so any
 * ALTER-shaped statement targeting a not-yet-created table needs the
 * {@code to_regclass(...)} DO-block guard (or the {@code IF EXISTS}
 * clause where the statement supports it).
 *
 * <p>Rather than run every migration (V3 and V4 are known-unsafe but
 * shipped, and editing them breaks prod Flyway checksum validation),
 * this test executes the "known-safe" migrations one at a time against
 * an empty Postgres and asserts each is a no-op-or-success. Any newly
 * added migration should be added to {@link #FRESH_DB_SAFE_MIGRATIONS}
 * once it's been reviewed for the pattern; a failure here means the
 * new migration violates the guard.
 *
 * <p>Guarded by {@code INTEGRATION_TESTS=1} so unit-only CI stays green.
 */
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "1")
class MigrationsFreshDbIntegrationTest {

    /**
     * Migrations that have been verified fresh-DB safe. Add new
     * migrations here after they've been reviewed for the DO-block
     * guard pattern. V3 and V4 are intentionally NOT listed — they
     * shipped without guards and cannot be edited without a prod
     * checksum-mismatch risk.
     */
    private static final String[] FRESH_DB_SAFE_MIGRATIONS = {
            "V2__sprint49_tier0_tier1_columns.sql",
            "V5__sprint50_tier05_E_scope_tightening.sql",
            "V6__sprint50_tier1_B_data_model.sql",
            "V7__post_audit_indexes.sql",
            "V8__post_audit_foreign_keys.sql",
    };

    protected static final PostgreSQLContainer<?> postgres;
    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("migrations_test")
                .withUsername("test")
                .withPassword("test");
        postgres.start();
    }

    @Test
    void freshDbSafeMigrationsApplyOnEmptyPostgres() {
        assertDoesNotThrow(() -> {
            try (Connection conn = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement stmt = conn.createStatement()) {
                for (String migration : FRESH_DB_SAFE_MIGRATIONS) {
                    String sql = readMigration(migration);
                    stmt.execute(sql);
                }
            }
        }, "A fresh-DB-safe migration errored on empty Postgres — a table the migration "
                + "referenced doesn't exist yet. Wrap the offending statement in a "
                + "to_regclass(...) DO block per docs/flyway-fresh-db-guard-pattern.md.");
    }

    /** Read a migration file from the project source tree. Tests run from
     *  {@code backend/}, so migrations live at {@code src/main/resources/...}. */
    private static String readMigration(String filename) throws Exception {
        Path path = Path.of("src/main/resources/db/migration", filename);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
