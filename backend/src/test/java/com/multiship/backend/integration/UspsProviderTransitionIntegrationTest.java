package com.multiship.backend.integration;

import com.multiship.backend.dto.UspsProviderReadinessDTO;
import com.multiship.backend.repository.SystemSettingRepository;
import com.multiship.backend.service.SystemSettingService;
import com.multiship.backend.service.UspsProviderReadinessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR-A Agent-A (optional) — integration test for the USPS_PROVIDER
 * readiness service against real Postgres via Testcontainers.
 *
 * <p>Exercises {@link UspsProviderReadinessService#check()} end-to-end:
 * seeds {@code system_setting} rows via the real {@link SystemSettingService}
 * + writes {@code carrier_account_ref} rows via {@link JdbcTemplate}, then
 * verifies the readiness snapshot flips as expected. No mocks below the
 * service boundary.
 *
 * <p>Reuses the {@code SECRETS_ENCRYPTION_KEY} from the base class so the
 * AES-GCM path is exercised for the platform-cred writes.
 *
 * <p>Self-provisions the three new usps_direct_* columns with {@code
 * ALTER TABLE ... ADD COLUMN IF NOT EXISTS} in {@link #seed()} so this
 * test doesn't need Agent C's entity update to land first (Flyway is off
 * in integration tests, so V58 hasn't run; Hibernate ddl-auto=update
 * won't add columns it doesn't know about).
 *
 * <p>Guarded by {@code INTEGRATION_TESTS=1}.
 */
@Import({ForbidOutboundHttpTestConfig.class, MockCarrierConnectorsTestConfig.class})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class UspsProviderTransitionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UspsProviderReadinessService readinessService;

    @Autowired
    private SystemSettingService settingService;

    @Autowired
    private SystemSettingRepository settingRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // The base class turns Flyway off (Hibernate owns the schema in
        // integration tests). Agent C hasn't shipped the entity fields for
        // the three new columns yet. Self-provision them so this test can
        // exercise the readiness scan without a cross-agent dependency;
        // harmless once Agent C lands the entity (Hibernate ddl-auto=update
        // won't drop or duplicate an already-present column).
        jdbc.execute("ALTER TABLE carrier_account_ref "
                + "ADD COLUMN IF NOT EXISTS usps_direct_account_number VARCHAR(50)");
        jdbc.execute("ALTER TABLE carrier_account_ref "
                + "ADD COLUMN IF NOT EXISTS usps_direct_crid VARCHAR(20)");
        jdbc.execute("ALTER TABLE carrier_account_ref "
                + "ADD COLUMN IF NOT EXISTS usps_direct_mid VARCHAR(20)");
        // Wipe any leftover rows from a prior run against the shared
        // container. Only touches our test-tagged tenant codes.
        jdbc.update("DELETE FROM carrier_account_ref WHERE customer_no LIKE 'IT-USPS-%'");
    }

    @AfterEach
    void cleanup() {
        settingRepo.findByKey(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_ID_KEY)
                .ifPresent(row -> settingRepo.deleteById(row.getId()));
        settingRepo.findByKey(UspsProviderReadinessService.USPS_PLATFORM_CLIENT_SECRET_KEY)
                .ifPresent(row -> settingRepo.deleteById(row.getId()));
        jdbc.update("DELETE FROM carrier_account_ref WHERE customer_no LIKE 'IT-USPS-%'");
    }

    /**
     * Sanity: default-state readiness reports currentProvider = STAMPS_COM
     * (the documented default when the setting is unset) and targetProvider
     * = USPS_DIRECT. Doesn't assert overallReady because other tests may
     * have seeded real accounts; only checks our test-scoped invariants.
     */
    @Test
    void freshDatabase_reportsDefaultProvider_targetIsUspsDirect() {
        UspsProviderReadinessDTO dto = readinessService.check();

        assertEquals("USPS_DIRECT", dto.getTargetProvider());
        assertEquals("STAMPS_COM", dto.getCurrentProvider(),
                "no stored USPS_PROVIDER → default STAMPS_COM");
        long ourAccounts = dto.getPendingAccounts().stream()
                .filter(p -> p.getTenantCode() != null && p.getTenantCode().startsWith("IT-USPS-"))
                .count();
        assertEquals(0, ourAccounts, "no test rows seeded → none of ours in pending");
    }

    /**
     * Full round-trip: seed both platform creds via the real
     * {@link SystemSettingService} + insert a fully-populated USPS account
     * via native SQL. The readiness scan finds our row as ready and reports
     * both creds as set. Then insert a pending row (missing CRID) — the
     * scan surfaces it as pending and overallReady flips false.
     */
    @Test
    void seedRealCredsAndAccount_flipsReadyThenPendingCorrectly() {
        // Real platform creds via the encrypt path (exercises AES-GCM).
        settingService.setEncrypted(
                UspsProviderReadinessService.USPS_PLATFORM_CLIENT_ID_KEY, "ck-integration-test", "it");
        settingService.setEncrypted(
                UspsProviderReadinessService.USPS_PLATFORM_CLIENT_SECRET_KEY, "cs-integration-test", "it");

        // Seed a USPS account with all three identifiers populated.
        // created_at / updated_at come from Hibernate @CreationTimestamp
        // when the entity path inserts, but a native INSERT bypasses that
        // so we set them explicitly.
        jdbc.update("INSERT INTO carrier_account_ref "
                + "  (account_number, carrier_code, account_name, customer_no, active, "
                + "   created_at, updated_at, "
                + "   usps_direct_account_number, usps_direct_crid, usps_direct_mid) "
                + "VALUES (?, 'USPS', 'IT USPS ready', 'IT-USPS-READY', TRUE, now(), now(), ?, ?, ?)",
                "IT-A-READY-" + System.nanoTime(), "EPS-1", "CRID-1", "MID-1");

        UspsProviderReadinessDTO ready = readinessService.check();
        assertTrue(ready.getPlatformCreds().isClientIdSet(),
                "clientId seeded via setEncrypted → clientIdSet must be true");
        assertTrue(ready.getPlatformCreds().isClientSecretSet(),
                "clientSecret seeded via setEncrypted → clientSecretSet must be true");
        long ourPending = ready.getPendingAccounts().stream()
                .filter(p -> p.getTenantCode() != null && p.getTenantCode().startsWith("IT-USPS-"))
                .count();
        assertEquals(0, ourPending, "our seeded row has all three identifiers → not pending");

        // Now insert a pending row (CRID = NULL).
        jdbc.update("INSERT INTO carrier_account_ref "
                + "  (account_number, carrier_code, account_name, customer_no, active, "
                + "   created_at, updated_at, "
                + "   usps_direct_account_number, usps_direct_crid, usps_direct_mid) "
                + "VALUES (?, 'USPS', 'IT USPS pending', 'IT-USPS-PENDING', TRUE, now(), now(), ?, NULL, ?)",
                "IT-A-PEND-" + System.nanoTime(), "EPS-2", "MID-2");

        UspsProviderReadinessDTO pending = readinessService.check();
        long ourPending2 = pending.getPendingAccounts().stream()
                .filter(p -> p.getTenantCode() != null && p.getTenantCode().startsWith("IT-USPS-"))
                .count();
        assertEquals(1, ourPending2, "the new row missing CRID must surface as pending");
        assertFalse(pending.isOverallReady(),
                "even one pending account (ours) blocks overallReady");
    }
}
