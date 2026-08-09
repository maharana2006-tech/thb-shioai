package com.multiship.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Sprint 49 Tier 0 — AES-GCM encryption for at-rest secrets.
 *
 * <p>Used by {@link com.multiship.backend.service.SystemSettingService}
 * to store the OpenAI API key (and other admin-managed secrets) in the
 * database without leaving plaintext on disk. Tier 1 will layer on top
 * for carrier client_secret encryption.
 *
 * <p>Key material comes from the {@code SECRETS_ENCRYPTION_KEY} env
 * var. The service does NOT fail-fast at construction — if the env var
 * is unset, encryption/decryption throws at first use so a fresh dev
 * environment can still boot with the feature simply disabled.
 *
 * <p>Wire format: {@code base64( nonce || ciphertext-with-tag )},
 * where nonce is 12 bytes and the GCM auth tag is 128 bits.
 */
@Slf4j
@Service
public class CryptoService {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int MIN_KEY_BYTES = 32;  // AES-256

    private final byte[] keyBytes;
    private final SecureRandom rng = new SecureRandom();

    public CryptoService(@Value("${secrets.encryption-key:}") String base64Key) {
        this.keyBytes = decodeKeyIfPresent(base64Key);
    }

    private static byte[] decodeKeyIfPresent(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("SECRETS_ENCRYPTION_KEY unset — DB-backed admin secrets disabled. "
                    + "Set to a base64-encoded 32-byte random value to enable.");
            return new byte[0];
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Key);
            if (decoded.length < MIN_KEY_BYTES) {
                throw new IllegalStateException(
                        "SECRETS_ENCRYPTION_KEY must decode to at least 32 bytes (AES-256); got "
                                + decoded.length);
            }
            // Truncate to exactly 32 bytes for AES-256.
            byte[] out = new byte[MIN_KEY_BYTES];
            System.arraycopy(decoded, 0, out, 0, MIN_KEY_BYTES);
            return out;
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException(
                    "SECRETS_ENCRYPTION_KEY must be base64-encoded", notBase64);
        }
    }

    public boolean isAvailable() {
        return keyBytes.length > 0;
    }

    public String encrypt(String plaintext) {
        requireAvailable();
        if (plaintext == null) return null;
        try {
            byte[] nonce = new byte[NONCE_LEN];
            rng.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ct, 0, combined, nonce.length, ct.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            throw new IllegalStateException("Encryption failed", ex);
        }
    }

    public String decrypt(String base64Ciphertext) {
        requireAvailable();
        if (base64Ciphertext == null || base64Ciphertext.isBlank()) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(base64Ciphertext);
            if (combined.length <= NONCE_LEN) {
                throw new IllegalStateException("Ciphertext too short to contain a nonce");
            }
            byte[] nonce = new byte[NONCE_LEN];
            byte[] ct = new byte[combined.length - NONCE_LEN];
            System.arraycopy(combined, 0, nonce, 0, NONCE_LEN);
            System.arraycopy(combined, NONCE_LEN, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (IllegalStateException ise) {
            throw ise;
        } catch (Exception ex) {
            throw new IllegalStateException("Decryption failed — key rotated or ciphertext tampered", ex);
        }
    }

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "SECRETS_ENCRYPTION_KEY is not configured; cannot encrypt/decrypt secrets");
        }
    }
}
