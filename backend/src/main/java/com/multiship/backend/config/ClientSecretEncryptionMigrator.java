package com.multiship.backend.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Sprint 49 Tier 1 — one-shot migration to encrypt existing plaintext
 * {@code client_secret} values on {@code carrier_config},
 * {@code carrier_account_ref}, and (Sprint 50 Tier 1 finding #2)
 * {@code users.carrier_client_secret}.
 *
 * <p>Detects unmigrated rows via a native query filtering for values that
 * don't carry the {@code enc:v1:} sentinel prefix. Encrypts each in-place
 * via a native UPDATE. Idempotent: subsequent startups find zero rows
 * and log a skip.
 *
 * <p>Native SQL is deliberate: going through JPA {@code save()} on a
 * managed entity is a no-op when the loaded attribute equals the DB
 * snapshot (which it always does for a legacy plaintext row —
 * {@link EncryptedStringConverter#convertToEntityAttribute} passes
 * unmigrated plaintext through unchanged). Hibernate's dirty check
 * then skips the UPDATE and the migration silently fails. Bypassing
 * the entity manager side-steps that entirely.
 *
 * <p>Guards: skips entirely when
 * {@link CryptoService#isAvailable()} is false so dev environments that
 * haven't set {@code SECRETS_ENCRYPTION_KEY} still boot without errors.
 */
@Slf4j
@Component
@Order(50)  // run before other CommandLineRunners that touch these tables
@RequiredArgsConstructor
public class ClientSecretEncryptionMigrator implements CommandLineRunner {

    private final CryptoService crypto;

    @PersistenceContext
    private EntityManager em;

    // Sprint 50 PR K post-audit finding H4 — DEFERRED. The audit flagged
    // that two instances booting concurrently could race on encryption of
    // rows inserted mid-migration. The clean fix (Postgres advisory lock
    // via pg_try_advisory_lock) needs the lock and the migration writes
    // on the SAME connection to work — Hikari's pool doesn't guarantee
    // that, so a naive @Transactional wrapper leaks the lock into other
    // pool connections and breaks concurrent test runs. The correct fix
    // is to extract migration into a separate @Component bean with a
    // single @Transactional method that acquires pg_try_advisory_xact_lock
    // and does all the encryption inline. That's a follow-up refactor;
    // for single-instance deployments (the common case) the current code
    // is safe. Multi-instance concurrent boot risk documented here.

    @Override
    public void run(String... args) {
        if (!crypto.isAvailable()) {
            log.warn("ClientSecretEncryptionMigrator: SECRETS_ENCRYPTION_KEY is unset; skipping migration.");
            return;
        }
        int carrierConfigCount = migrateCarrierConfig();
        int accountRefCount = migrateCarrierAccountRef();
        int userCount = migrateUser();
        if (carrierConfigCount == 0 && accountRefCount == 0 && userCount == 0) {
            log.info("ClientSecretEncryptionMigrator: nothing to migrate — all client_secret values already encrypted.");
        } else {
            log.info("ClientSecretEncryptionMigrator: encrypted {} carrier_config + {} carrier_account_ref + {} users rows.",
                    carrierConfigCount, accountRefCount, userCount);
        }
    }


    @Transactional
    protected int migrateCarrierConfig() {
        return encryptPlaintextColumn("carrier_config", "client_secret");
    }

    @Transactional
    protected int migrateCarrierAccountRef() {
        return encryptPlaintextColumn("carrier_account_ref", "client_secret");
    }

    /**
     * Sprint 50 Tier 1 finding #2 — backfill User.carrier_client_secret.
     */
    @Transactional
    protected int migrateUser() {
        return encryptPlaintextColumn("users", "carrier_client_secret");
    }

    /**
     * Read every plaintext row for {@code table.column}, run each value
     * through {@link CryptoService#encrypt}, and write it back with the
     * {@code enc:v1:} sentinel via a native UPDATE. See the class-level
     * javadoc for why native SQL rather than JPA {@code save()}.
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
