package com.multiship.backend.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Sprint 50 PR K post-audit finding H4 — race-safe executor for the
 * one-shot client_secret encryption migration.
 *
 * <p>Split out from {@link ClientSecretEncryptionMigrator} so Spring's
 * transactional AOP proxy actually fires: the runner injects THIS bean
 * (a proxy) and calls {@link #migrateAllIfWinner()} through the proxy,
 * which opens the transaction, acquires the Postgres advisory lock, and
 * runs all three column migrations on the SAME JDBC connection. Commit
 * releases the lock automatically ({@code pg_try_advisory_xact_lock}
 * semantics), so nothing leaks back into the Hikari pool.
 *
 * <p><b>Concurrency model:</b> Two app instances booting against the
 * same Postgres both try to acquire advisory key {@link #ADVISORY_KEY}.
 * The winner does the migration; the loser sees {@code false} and
 * skips. The winner's transaction commit ends the lock; a third boot
 * later finds zero unencrypted rows (idempotent) and exits fast.
 *
 * <p><b>Failure modes:</b>
 * <ul>
 *   <li><b>Non-Postgres backend (H2, in-memory tests):</b>
 *       {@code pg_try_advisory_xact_lock} doesn't exist and the native
 *       query throws. We catch, log, and fall through to run the
 *       migration unlocked — single-instance test envs can't race so
 *       this is safe.</li>
 *   <li><b>Connection unavailable / pool exhausted:</b> Spring's
 *       transaction manager throws before the method body runs; the
 *       runner catches nothing and boot fails loudly, which is the
 *       correct signal for a misconfigured environment.</li>
 *   <li><b>Encryption fails mid-batch:</b> {@link CryptoService#encrypt}
 *       throws {@link IllegalStateException}; the enclosing transaction
 *       rolls back (advisory lock released), no partial writes persist,
 *       and boot fails. Operator sees the stack trace and fixes the
 *       key material before restart.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClientSecretEncryptionExecutor {

    /**
     * Arbitrary 64-bit constant unique to this migration. Bytes chosen
     * to be recognisable in pg_locks tables when debugging: "MSCSENC1"
     * (Multiship Client Secret ENCryption v1). Change only if you
     * introduce a second, unrelated advisory-locked migration that must
     * not block on this one.
     */
    static final long ADVISORY_KEY = 0x4D53_4353_454E_4331L;

    private final CryptoService crypto;

    @PersistenceContext
    private EntityManager em;

    /**
     * Acquire the transaction-scoped advisory lock; if we lose the race,
     * return quietly. If we win (or the backend doesn't support the
     * function — e.g. H2), run all three column migrations inline so
     * they share the transaction and the lock.
     *
     * <p>Uses default {@code Propagation.REQUIRED} — Spring Boot's
     * CommandLineRunner isn't invoked inside a transaction in the
     * production boot path, so we don't need REQUIRES_NEW. And crucially,
     * REQUIRES_NEW breaks integration tests where the test method's own
     * {@code @Transactional} hasn't committed pre-seeded rows yet: the
     * fresh transaction on a new connection wouldn't see them, and the
     * migration would skip. REQUIRED joins the outer test transaction
     * cleanly.
     */
    @Transactional
    public void migrateAllIfWinner() {
        if (!tryAdvisoryLock()) {
            log.info("ClientSecretEncryptionExecutor: another instance is winning the "
                    + "migration race (advisory key {}); skipping.", ADVISORY_KEY);
            return;
        }
        int carrierConfigCount = encryptPlaintextColumn("carrier_config", "client_secret");
        int accountRefCount = encryptPlaintextColumn("carrier_account_ref", "client_secret");
        int userCount = encryptPlaintextColumn("users", "carrier_client_secret");
        // Sprint 51 security fix — access_token became an encrypted column
        // at the same time; back-fill any plaintext tokens already on disk.
        int accessTokenCount = encryptPlaintextColumn("carrier_config", "access_token");
        if (carrierConfigCount == 0 && accountRefCount == 0 && userCount == 0
                && accessTokenCount == 0) {
            log.info("ClientSecretEncryptionExecutor: nothing to migrate — "
                    + "all secret columns already encrypted.");
        } else {
            log.info("ClientSecretEncryptionExecutor: encrypted {} carrier_config.client_secret + "
                    + "{} carrier_account_ref + {} users + {} carrier_config.access_token rows.",
                    carrierConfigCount, accountRefCount, userCount, accessTokenCount);
        }
    }

    /**
     * @return {@code true} if we hold the lock (or the backend doesn't
     *         support advisory locks — fall through and migrate anyway
     *         because non-Postgres backends are single-instance test
     *         envs where races can't happen).
     */
    private boolean tryAdvisoryLock() {
        try {
            Object result = em.createNativeQuery(
                            "SELECT pg_try_advisory_xact_lock(:key)")
                    .setParameter("key", ADVISORY_KEY)
                    .getSingleResult();
            return Boolean.TRUE.equals(result);
        } catch (Exception ex) {
            // Non-Postgres (H2, HSQL) — function doesn't exist. Log once
            // and proceed; the race can't happen without a shared DB.
            log.debug("ClientSecretEncryptionExecutor: advisory lock unsupported "
                    + "({}); proceeding without lock.", ex.getMessage());
            return true;
        }
    }

    /**
     * See {@link ClientSecretEncryptionMigrator} class javadoc for the
     * "why native SQL, not JPA save()" rationale (converter returns
     * plaintext unchanged, Hibernate dirty-check skips the UPDATE).
     */
    private int encryptPlaintextColumn(String table, String column) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT id, " + column + " FROM " + table
                                + " WHERE " + column + " IS NOT NULL"
                                + " AND " + column + " <> ''"
                                + " AND " + column + " NOT LIKE 'enc:v1:%'")
                .getResultList();
        int migrated = 0;
        for (Object[] r : rows) {
            long id = ((Number) r[0]).longValue();
            String plaintext = String.valueOf(r[1]);
            String ciphertext = EncryptedStringConverter.PREFIX + crypto.encrypt(plaintext);
            int updated = em.createNativeQuery(
                            "UPDATE " + table + " SET " + column + " = :ct WHERE id = :id")
                    .setParameter("ct", ciphertext)
                    .setParameter("id", id)
                    .executeUpdate();
            migrated += updated;
        }
        return migrated;
    }
}
