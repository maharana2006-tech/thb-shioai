package com.multiship.backend.service;

import com.multiship.backend.config.CryptoService;
import com.multiship.backend.model.SystemSetting;
import com.multiship.backend.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Sprint 49 Tier 0 — admin-managed secrets.
 *
 * <p>Encapsulates AES-GCM encryption via {@link CryptoService}.
 * Consumers such as {@link com.multiship.backend.service.ai.OpenAiClient}
 * call {@link #getDecrypted(String)} to overlay a DB-stored value over
 * their {@code @Value}-injected env-var fallback.
 *
 * <p>The service tolerates a missing encryption key at read time
 * (returns empty + logs), so a fresh dev environment keeps booting.
 * Writes always require the key.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemSettingService {

    private final SystemSettingRepository repository;
    private final CryptoService crypto;

    /**
     * Decrypts the stored value for {@code key}. Returns empty if no row
     * exists, if the encryption key is unset, or if decryption fails
     * (logged at WARN — caller falls back to its own default).
     */
    public Optional<String> getDecrypted(String key) {
        Optional<SystemSetting> row = repository.findByKey(key);
        if (row.isEmpty()) return Optional.empty();

        String cipher = row.get().getEncryptedValue();
        if (cipher == null || cipher.isBlank()) return Optional.empty();

        if (!crypto.isAvailable()) {
            log.warn("SystemSetting[{}] present but SECRETS_ENCRYPTION_KEY is unset; skipping.", key);
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(crypto.decrypt(cipher));
        } catch (Exception ex) {
            log.warn("SystemSetting[{}] decrypt failed: {}", key, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Upserts a setting. Fails loudly if the encryption key is unset —
     * writes should never store plaintext.
     */
    @Transactional
    public void setEncrypted(String key, String plaintext, String actor) {
        if (!crypto.isAvailable()) {
            throw new IllegalStateException(
                    "SECRETS_ENCRYPTION_KEY is not configured; cannot store admin settings.");
        }
        SystemSetting row = repository.findByKey(key).orElseGet(SystemSetting::new);
        row.setKey(key);
        row.setEncryptedValue(crypto.encrypt(plaintext));
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setUpdatedBy(actor);
        repository.save(row);
    }

    /** True if a value is stored (regardless of whether we can decrypt it). */
    public boolean has(String key) {
        return repository.findByKey(key)
                .map(SystemSetting::getEncryptedValue)
                .filter(s -> !s.isBlank())
                .isPresent();
    }

    /** Returns "****" + last 4 chars of the decrypted value, or empty. */
    public Optional<String> maskedPreview(String key) {
        return getDecrypted(key).map(SystemSettingService::mask);
    }

    static String mask(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.length() <= 4) return "****";
        return "****" + s.substring(s.length() - 4);
    }
}
