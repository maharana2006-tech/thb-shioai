package com.multiship.backend.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Sprint 49 Tier 1 — JPA converter that encrypts columns at rest.
 *
 * <p>Wire format uses an {@code enc:v1:} sentinel so read paths can
 * distinguish already-encrypted values from unmigrated plaintext (kept
 * for backward-compat during the one-shot migration on startup — see
 * {@link com.multiship.backend.config.ClientSecretEncryptionMigrator}).
 *
 * <p>When the encryption key is unset, the converter degrades to a
 * no-op instead of failing: fresh dev environments boot without needing
 * the env var. Writes to encrypted fields in that mode will store
 * plaintext; the migrator won't run either. Set
 * {@code SECRETS_ENCRYPTION_KEY} in every non-dev env.
 */
@Slf4j
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    static final String PREFIX = "enc:v1:";

    private final CryptoService crypto;

    public EncryptedStringConverter(CryptoService crypto) {
        this.crypto = crypto;
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        if (!crypto.isAvailable()) {
            // Backward compat: pass through so a dev-mode boot without key
            // doesn't corrupt data. Prod deploys MUST set the key.
            return plaintext;
        }
        if (plaintext.startsWith(PREFIX)) {
            // Already encrypted (shouldn't happen in normal flow, but guards
            // against a re-save loop if data was hand-edited).
            return plaintext;
        }
        return PREFIX + crypto.encrypt(plaintext);
    }

    @Override
    public String convertToEntityAttribute(String dbValue) {
        if (dbValue == null || dbValue.isEmpty()) return dbValue;
        if (!dbValue.startsWith(PREFIX)) {
            // Unmigrated plaintext row — return as-is; the migrator will
            // re-save it and the write path will encrypt.
            return dbValue;
        }
        if (!crypto.isAvailable()) {
            log.warn("Encrypted column encountered but SECRETS_ENCRYPTION_KEY is unset; returning ciphertext.");
            return null;
        }
        try {
            return crypto.decrypt(dbValue.substring(PREFIX.length()));
        } catch (Exception ex) {
            log.warn("Failed to decrypt column (key rotated or tampered): {}", ex.getMessage());
            return null;
        }
    }
}
