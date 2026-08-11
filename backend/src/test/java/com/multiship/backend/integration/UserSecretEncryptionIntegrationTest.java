package com.multiship.backend.integration;

import com.multiship.backend.config.ClientSecretEncryptionMigrator;
import com.multiship.backend.model.User;
import com.multiship.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 50 Tier 1 finding #2 — end-to-end proof that
 * {@code User.carrier_client_secret} is encrypted at rest by
 * {@link com.multiship.backend.config.EncryptedStringConverter} and that
 * {@link ClientSecretEncryptionMigrator} backfills legacy plaintext
 * rows on startup.
 *
 * <p>Runs against a real Postgres via {@link AbstractIntegrationTest}
 * (Testcontainers). Guarded by {@code INTEGRATION_TESTS=1} — CI without
 * Docker still runs the full unit suite green.
 */
class UserSecretEncryptionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ClientSecretEncryptionMigrator migrator;

    @PersistenceContext
    private EntityManager em;

    /**
     * Save via JPA → column stores {@code enc:v1:…} ciphertext, and
     * a fresh load returns the original plaintext.
     */
    @Test
    @Transactional
    void carrierClientSecretIsCipherAtRestAndPlaintextOnLoad() {
        User u = User.builder()
                .username("enc-user-1")
                .email("enc-user-1@example.com")
                .password("pw")
                .fullName("Enc User One")
                .role("USER")
                .carrierClientSecret("plaintext-secret-42")
                .build();
        u = userRepository.save(u);

        // Force the converter's write path + kick the entity out of the
        // 1st-level cache so the subsequent findById triggers a real
        // SELECT + convertToEntityAttribute.
        em.flush();
        em.clear();

        // Raw column read — bypasses the converter, shows what the DB
        // actually stored.
        Object raw = em.createNativeQuery(
                        "SELECT carrier_client_secret FROM users WHERE id = :id")
                .setParameter("id", u.getId())
                .getSingleResult();
        assertNotNull(raw, "raw column should be populated");
        String rawStr = raw.toString();
        assertTrue(rawStr.startsWith("enc:v1:"),
                "raw column must start with enc:v1: sentinel — was: " + rawStr);

        // JPA reload — converter decrypts back to the original plaintext.
        User reloaded = userRepository.findById(u.getId()).orElseThrow();
        assertEquals("plaintext-secret-42", reloaded.getCarrierClientSecret(),
                "JPA read should return the original plaintext");
    }

    /**
     * Pre-seed a plaintext row via native SQL (simulating a legacy row
     * from before finding #2 was fixed) and prove
     * {@link ClientSecretEncryptionMigrator#run} re-writes it so the raw
     * column then carries the {@code enc:v1:} prefix.
     */
    @Test
    @Transactional
    void migratorEncryptsLegacyPlaintextRows() {
        // First insert a row via JPA to get a valid id + NOT-NULL columns.
        User u = User.builder()
                .username("legacy-user-1")
                .email("legacy-user-1@example.com")
                .password("pw")
                .fullName("Legacy User One")
                .role("USER")
                // seed something so the row has the encrypted shape;
                // we'll overwrite via native SQL next.
                .carrierClientSecret("initial-anything")
                .build();
        u = userRepository.save(u);
        em.flush();

        // Now stomp the column with legacy plaintext to simulate a row
        // from before the converter was wired in.
        int updated = em.createNativeQuery(
                        "UPDATE users SET carrier_client_secret = 'plaintext-legacy' WHERE id = :id")
                .setParameter("id", u.getId())
                .executeUpdate();
        assertEquals(1, updated);
        em.clear();

        // Sanity: pre-migration raw column is bare plaintext.
        Object rawBefore = em.createNativeQuery(
                        "SELECT carrier_client_secret FROM users WHERE id = :id")
                .setParameter("id", u.getId())
                .getSingleResult();
        assertEquals("plaintext-legacy", rawBefore.toString(),
                "pre-migration row should still be plaintext");

        // Run the full migrator — its user path picks up our seeded row.
        // run() is the public CommandLineRunner entry point; migrateUser
        // is protected and would need reflection to invoke directly.
        migrator.run();

        // Force Hibernate to push any pending UPDATE to the DB before we
        // read the raw column back, then evict entities so the follow-up
        // findById re-hits the DB.
        em.flush();
        em.clear();

        Object rawAfter = em.createNativeQuery(
                        "SELECT carrier_client_secret FROM users WHERE id = :id")
                .setParameter("id", u.getId())
                .getSingleResult();
        assertNotNull(rawAfter);
        String rawAfterStr = rawAfter.toString();
        assertTrue(rawAfterStr.startsWith("enc:v1:"),
                "post-migration raw column must be encrypted — was: " + rawAfterStr);

        // And the JPA read still returns the original plaintext.
        User reloaded = userRepository.findById(u.getId()).orElseThrow();
        assertEquals("plaintext-legacy", reloaded.getCarrierClientSecret(),
                "post-migration JPA read must decrypt back to the legacy plaintext");
    }
}
