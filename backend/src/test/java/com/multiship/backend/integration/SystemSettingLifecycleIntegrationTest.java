package com.multiship.backend.integration;

import com.multiship.backend.repository.SystemSettingRepository;
import com.multiship.backend.service.SystemSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 53 system-be-integration — full system-settings lifecycle
 * against real Postgres via Testcontainers, exercising the AES-GCM
 * encrypt → store → decrypt round-trip end-to-end.
 *
 * <p>The base {@link AbstractIntegrationTest} provides a real
 * {@code SECRETS_ENCRYPTION_KEY} (all-zero 32-byte key for tests) so
 * {@link com.multiship.backend.config.CryptoService#isAvailable()}
 * returns true and the write path exercises real GCM math.
 *
 * <p>Anti-fallback: reuses {@link MockCarrierConnectorsTestConfig} +
 * {@link ForbidOutboundHttpTestConfig}. No carrier IO possible.
 *
 * <p>Rows namespaced with unique test-run keys so re-runs against the
 * shared container stay clean.
 *
 * <p>Guarded by {@code INTEGRATION_TESTS=1}.
 */
@Import({ForbidOutboundHttpTestConfig.class, MockCarrierConnectorsTestConfig.class})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class SystemSettingLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SystemSettingService service;
    @Autowired
    private SystemSettingRepository repo;

    /** Namespace so re-runs don't collide with each other or with the
     *  real registered {@code openai.api-key} rows other tests may have set. */
    private String testKey;

    @BeforeEach
    void setUp() {
        // Unique per-run key. openai.api-key IS the registered production
        // key; using a namespaced test key means we exercise the service
        // without polluting real config, and cleanup is trivial.
        testKey = "test.system-it." + System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        repo.findByKey(testKey).ifPresent(row -> repo.deleteById(row.getId()));
    }

    // ================ 1. ROUND-TRIP encrypt → decrypt ================

    @Test
    void setEncrypted_thenGetDecrypted_returnsOriginalPlaintext() {
        service.setEncrypted(testKey, "sk-plain-abcd1234", "admin-it");

        Optional<String> back = service.getDecrypted(testKey);

        assertTrue(back.isPresent());
        assertEquals("sk-plain-abcd1234", back.get(),
                "AES-GCM round-trip must return the exact original plaintext.");
    }

    // ================ 2. AT-REST: cipher text differs from plaintext ================

    @Test
    void setEncrypted_persistsCipherText_notPlaintext() {
        service.setEncrypted(testKey, "sk-plain-abcd1234", "admin-it");

        String storedCipher = repo.findByKey(testKey).orElseThrow().getEncryptedValue();

        assertNotNull(storedCipher);
        assertFalse(storedCipher.contains("sk-plain"),
                "Stored value must NOT contain the plaintext (AES-GCM ciphertext + base64 envelope).");
        assertTrue(storedCipher.length() > 20,
                "Cipher blob should be substantially longer than the plaintext (nonce + tag + b64).");
    }

    // ================ 3. HAS + MASKED PREVIEW ================

    @Test
    void has_returnsFalseBeforeSet_trueAfter() {
        assertFalse(service.has(testKey));

        service.setEncrypted(testKey, "value", "admin-it");

        assertTrue(service.has(testKey));
    }

    @Test
    void maskedPreview_returnsFourStarsPlusLastFourAfterSet() {
        service.setEncrypted(testKey, "sk-openai-abcd1234", "admin-it");

        Optional<String> masked = service.maskedPreview(testKey);

        assertTrue(masked.isPresent());
        assertEquals("****1234", masked.get());
    }

    // ================ 4. OVERWRITE ================

    @Test
    void setEncrypted_overwritesExistingRow_notInserted() {
        service.setEncrypted(testKey, "first", "admin-it");
        Long firstId = repo.findByKey(testKey).orElseThrow().getId();

        service.setEncrypted(testKey, "second", "admin-it");
        var refetched = repo.findByKey(testKey).orElseThrow();

        // Same id — update, not insert.
        assertEquals(firstId, refetched.getId(),
                "setEncrypted on an existing key must UPDATE in place, not insert.");
        // New plaintext round-trips.
        assertEquals("second", service.getDecrypted(testKey).orElseThrow());
    }

    @Test
    void setEncrypted_advancesUpdatedAt_andUpdatesActor() throws Exception {
        service.setEncrypted(testKey, "first", "admin-A");
        var original = repo.findByKey(testKey).orElseThrow();
        var originalUpdatedAt = original.getUpdatedAt();

        // 2ms guarantee for the LocalDateTime comparison.
        Thread.sleep(2);
        service.setEncrypted(testKey, "second", "admin-B");
        var updated = repo.findByKey(testKey).orElseThrow();

        assertTrue(updated.getUpdatedAt().isAfter(originalUpdatedAt),
                "updatedAt must advance on overwrite.");
        assertEquals("admin-B", updated.getUpdatedBy(),
                "updatedBy must reflect the NEW actor, not the prior one.");
    }

    // ================ 5. UNIQUE KEY CONSTRAINT ================

    @Test
    void setEncrypted_isIdempotentAcrossManyCalls_andKeepsOneRowPerKey() {
        service.setEncrypted(testKey, "a", "admin-it");
        service.setEncrypted(testKey, "b", "admin-it");
        service.setEncrypted(testKey, "c", "admin-it");

        // Confirm exactly ONE row exists for this key (the unique constraint
        // + our upsert logic prevents duplicates).
        long count = repo.findAll().stream()
                .filter(r -> testKey.equals(r.getKey()))
                .count();
        assertEquals(1, count);
        assertEquals("c", service.getDecrypted(testKey).orElseThrow());
    }

    // ================ 6. GET DECRYPTED MISSING ================

    @Test
    void getDecrypted_unknownKey_returnsEmpty() {
        // No row seeded for this key — read gracefully returns empty.
        Optional<String> result = service.getDecrypted("no.such.key.ever");
        assertTrue(result.isEmpty());
    }
}
