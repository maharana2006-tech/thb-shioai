package com.multiship.backend.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 49 Tier 1 — round-trip guard on the encrypted-column converter.
 *
 * <p>The converter has three delicate behaviors that regressions would
 * silently break: (a) round-trip identity when the key is available;
 * (b) pass-through on read for unmigrated plaintext rows (backward
 * compat during the one-shot migration); (c) pass-through on write
 * when the key is unset (dev-mode boot must not corrupt data).
 */
class EncryptedStringConverterTest {

    private static String base64Key32Bytes() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) i;
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void roundTripPreservesValue() {
        CryptoService crypto = new CryptoService(base64Key32Bytes());
        EncryptedStringConverter conv = new EncryptedStringConverter(crypto);

        String plaintext = "sk-abc-1234567890-secret";
        String db = conv.convertToDatabaseColumn(plaintext);
        assertTrue(db.startsWith("enc:v1:"), "wire format must carry the version sentinel");
        assertNotEquals(plaintext, db, "DB value must not be plaintext");

        String back = conv.convertToEntityAttribute(db);
        assertEquals(plaintext, back);
    }

    @Test
    void unmigratedPlaintextReadPassesThrough() {
        // Backward compat: rows that pre-date encryption come back as-is
        // on read so the migrator can then re-save them (which encrypts).
        CryptoService crypto = new CryptoService(base64Key32Bytes());
        EncryptedStringConverter conv = new EncryptedStringConverter(crypto);

        String legacy = "raw-plaintext-secret-from-before-tier1";
        assertEquals(legacy, conv.convertToEntityAttribute(legacy));
    }

    @Test
    void nullAndEmptyPassThrough() {
        CryptoService crypto = new CryptoService(base64Key32Bytes());
        EncryptedStringConverter conv = new EncryptedStringConverter(crypto);

        assertNull(conv.convertToDatabaseColumn(null));
        assertEquals("", conv.convertToDatabaseColumn(""));
        assertNull(conv.convertToEntityAttribute(null));
        assertEquals("", conv.convertToEntityAttribute(""));
    }

    @Test
    void writePassesThroughWhenKeyUnset() {
        // Dev-mode boot without SECRETS_ENCRYPTION_KEY must not throw on
        // write. Plaintext lands in the DB; deploys are expected to set
        // the env var + run the migrator.
        CryptoService crypto = new CryptoService(null);  // key unset
        EncryptedStringConverter conv = new EncryptedStringConverter(crypto);

        String plaintext = "some-value";
        assertEquals(plaintext, conv.convertToDatabaseColumn(plaintext),
                "with no key, write must pass through — never throw during dev boot");
    }

    @Test
    void doubleEncryptGuard() {
        // If someone hand-edits a DB row to already carry the enc:v1:
        // prefix + valid ciphertext, and the app then round-trips it,
        // we must NOT double-encrypt on write.
        CryptoService crypto = new CryptoService(base64Key32Bytes());
        EncryptedStringConverter conv = new EncryptedStringConverter(crypto);

        String encrypted = conv.convertToDatabaseColumn("original");
        // convertToDatabaseColumn on an already-prefixed value returns as-is
        assertEquals(encrypted, conv.convertToDatabaseColumn(encrypted));
    }
}
