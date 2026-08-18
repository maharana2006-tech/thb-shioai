package com.multiship.backend.service.external;

import com.multiship.backend.config.CryptoService;
import com.multiship.backend.model.ExternalWebhookSubscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Audit R2 #336 — pins the envelope-encryption behavior for the webhook
 * HMAC secrets column.
 */
class WebhookSecretCipherTest {

    private CryptoService crypto;
    private WebhookSecretCipher cipher;

    @BeforeEach
    void setUp() {
        crypto = mock(CryptoService.class);
        when(crypto.isAvailable()).thenReturn(true);
        when(crypto.encrypt(anyString())).thenAnswer(inv -> "enc(" + inv.getArgument(0) + ")");
        when(crypto.decrypt(anyString())).thenAnswer(inv -> {
            String v = inv.getArgument(0);
            // Reverse the enc() wrapper for round-trip fidelity.
            if (v != null && v.startsWith("enc(") && v.endsWith(")")) {
                return v.substring(4, v.length() - 1);
            }
            return v;
        });
        cipher = new WebhookSecretCipher(crypto);
    }

    @Test
    void encryptOnSave_writesEncryptedAndNullsPlaintext() {
        ExternalWebhookSubscription sub = new ExternalWebhookSubscription();
        sub.setSecret("legacy-plaintext-that-should-vanish");

        cipher.encryptOnSave(sub, "hunter2");

        assertEquals("enc(hunter2)", sub.getSecretEncrypted());
        assertEquals(WebhookSecretCipher.CURRENT_KEY_ID, sub.getSecretKeyId());
        assertNull(sub.getSecret(), "plaintext column MUST be nulled so a mixed row never exists");
    }

    @Test
    void encryptOnSave_blankPlaintextLeavesRowUntouched() {
        // Edit-without-secret-change flow — caller sends null / blank so
        // we keep the row's current encrypted form intact.
        ExternalWebhookSubscription sub = new ExternalWebhookSubscription();
        sub.setSecretEncrypted("enc(previous)");
        sub.setSecretKeyId(WebhookSecretCipher.CURRENT_KEY_ID);

        cipher.encryptOnSave(sub, "");

        assertEquals("enc(previous)", sub.getSecretEncrypted());
    }

    @Test
    void encryptOnSave_cryptoUnavailable_throws() {
        when(crypto.isAvailable()).thenReturn(false);
        ExternalWebhookSubscription sub = new ExternalWebhookSubscription();

        // Audit R2 #347 — dedicated CryptoUnavailableException replaces
        // the generic IllegalStateException so the controller layer can
        // shape a 503 with the CRYPTO_UNAVAILABLE error code.
        com.multiship.backend.config.CryptoUnavailableException ex = assertThrows(
                com.multiship.backend.config.CryptoUnavailableException.class,
                () -> cipher.encryptOnSave(sub, "hunter2"));
        // Message should be actionable — mentions the env var + why we refuse.
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("SECRETS_ENCRYPTION_KEY"),
                "expected error message to name the missing env var, got: " + ex.getMessage());
    }

    @Test
    void resolveForDispatch_prefersEncryptedForm() {
        ExternalWebhookSubscription sub = new ExternalWebhookSubscription();
        sub.setSecret("legacy-should-not-be-used");
        sub.setSecretEncrypted("enc(hunter2)");
        sub.setSecretKeyId(WebhookSecretCipher.CURRENT_KEY_ID);

        assertEquals("hunter2", cipher.resolveForDispatch(sub));
    }

    @Test
    void resolveForDispatch_fallsBackToLegacyPlaintext() {
        // Pre-#336 row — has only the plaintext column populated.
        ExternalWebhookSubscription sub = new ExternalWebhookSubscription();
        sub.setSecret("legacy-plaintext");

        assertEquals("legacy-plaintext", cipher.resolveForDispatch(sub));
    }

    @Test
    void resolveForDispatch_bothNull_returnsNull() {
        // Corrupt state — dispatcher's HMAC step handles null secret by
        // returning null signature; receiver rejects. Better than a crash.
        ExternalWebhookSubscription sub = new ExternalWebhookSubscription();

        assertNull(cipher.resolveForDispatch(sub));
    }

    @Test
    void resolveForDispatch_decryptFails_returnsNullAndLogsRatherThanCrashes() {
        when(crypto.decrypt(anyString())).thenThrow(
                new IllegalStateException("Decryption failed — key rotated or ciphertext tampered"));
        ExternalWebhookSubscription sub = new ExternalWebhookSubscription();
        sub.setSecretEncrypted("enc(garbage)");
        sub.setSecretKeyId(WebhookSecretCipher.CURRENT_KEY_ID);
        // No plaintext fallback = null return. This is safer than crashing
        // the dispatcher thread on a single bad row.

        assertNull(cipher.resolveForDispatch(sub));
    }
}
