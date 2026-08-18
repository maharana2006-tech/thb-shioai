package com.multiship.backend.service.external;

import com.multiship.backend.config.CryptoService;
import com.multiship.backend.model.ExternalWebhookSubscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Audit R2 #336 — envelope encryption for HMAC webhook secrets.
 *
 * <p>Pre-fix, {@link ExternalWebhookSubscription#getSecret()} held the
 * signing key as plaintext in the DB. A DB dump = every tenant's HMAC
 * key leaked. Now:
 *
 * <ol>
 *   <li>{@link #encryptOnSave} wraps the plaintext with {@link CryptoService}
 *       (AES-GCM, 12-byte nonce, 128-bit tag, base64 wire format) and
 *       stamps {@link #CURRENT_KEY_ID} so future rotation knows which
 *       key material produced this row.</li>
 *   <li>{@link #resolveForDispatch} decrypts back to plaintext for the
 *       HMAC signing step. Falls back to the legacy plaintext column so
 *       pre-#336 rows keep signing during the transition — a future PR
 *       drops the plaintext column after ops backfills.</li>
 * </ol>
 *
 * <p>The {@link CryptoService} availability check happens on
 * {@link #encryptOnSave} — if the key isn't configured we throw a
 * dedicated {@link IllegalStateException} the controller layer surfaces
 * as 503 rather than silently persisting plaintext (would defeat the
 * point of the migration).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookSecretCipher {

    /**
     * Current encryption key generation. Bumped when new key material
     * is added to the CryptoService (multi-key support is a future
     * refactor; for now the service holds exactly one key and this
     * constant is always 1).
     */
    public static final short CURRENT_KEY_ID = (short) 1;

    private final CryptoService cryptoService;

    /**
     * Called by the CRUD controllers on save (POST + PUT). Encrypts the
     * caller-supplied plaintext + stamps the key id + NULLs out the
     * legacy plaintext column so we never keep both around.
     *
     * @param subscription the row about to be persisted; mutated in place
     * @param plaintext    the fresh secret to store; when null/blank we
     *                     leave the row's existing encrypted form intact
     *                     (edit-without-secret-change flow)
     */
    public void encryptOnSave(ExternalWebhookSubscription subscription, String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return;
        if (!cryptoService.isAvailable()) {
            // Audit R2 #347 — dedicated exception (shared with SFTP secret
            // path) so callers can shape a clean 503 CRYPTO_UNAVAILABLE.
            throw new com.multiship.backend.config.CryptoUnavailableException(
                    "SECRETS_ENCRYPTION_KEY is not configured; refusing to persist a "
                    + "webhook subscription with a plaintext secret. Set the env var "
                    + "to a base64 32-byte AES-256 key and retry.");
        }
        subscription.setSecretEncrypted(cryptoService.encrypt(plaintext));
        subscription.setSecretKeyId(CURRENT_KEY_ID);
        // Belt-and-braces: NULL the plaintext column so a mixed row (both
        // encrypted + plaintext) never exists. Pre-#336 rows without
        // secretEncrypted keep their plaintext until the next edit.
        subscription.setSecret(null);
    }

    /**
     * Called by {@link ExternalWebhookDispatcher} to get the raw HMAC
     * key for the signing step. Prefers the encrypted form; falls back
     * to the legacy plaintext column so pre-#336 rows keep working.
     *
     * @return the plaintext secret, or {@code null} when the row has
     *         neither form (a corrupt state — dispatcher logs + skips).
     */
    public String resolveForDispatch(ExternalWebhookSubscription subscription) {
        if (subscription.getSecretEncrypted() != null
                && !subscription.getSecretEncrypted().isBlank()) {
            if (!cryptoService.isAvailable()) {
                log.warn("Webhook subscription {} has an encrypted secret but "
                        + "SECRETS_ENCRYPTION_KEY is unset — cannot sign. Set the env var.",
                        subscription.getId());
                return null;
            }
            try {
                return cryptoService.decrypt(subscription.getSecretEncrypted());
            } catch (Exception ex) {
                // Key rotation without a re-encrypt sweep, or ciphertext
                // tampering. Fall through to the plaintext column (which
                // is likely NULL post-migration) so we return null +
                // log a warning rather than crashing.
                log.warn("Webhook subscription {} decryption failed (key rotated?): {}",
                        subscription.getId(), ex.getMessage());
            }
        }
        // Legacy plaintext fallback for pre-#336 rows.
        return subscription.getSecret();
    }
}
