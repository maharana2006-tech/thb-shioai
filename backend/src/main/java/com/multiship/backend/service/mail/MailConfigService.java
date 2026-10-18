package com.multiship.backend.service.mail;

import com.multiship.backend.config.CryptoService;
import com.multiship.backend.model.MailConfigEntity;
import com.multiship.backend.model.MailProviderEntity;
import com.multiship.backend.repository.MailConfigRepository;
import com.multiship.backend.repository.MailProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A4.1 — CRUD over {@code mail_provider} + {@code mail_config}. Handles secret
 * encryption via {@link CryptoService} (same pattern as
 * {@link com.multiship.backend.service.externalsystems.ExternalSystemConfigService}),
 * enforces the "one active provider" rule at the app layer as a friendly error
 * (the DB-level partial unique index is the ultimate guard).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailConfigService {

    private final MailProviderRepository providerRepo;
    private final MailConfigRepository configRepo;
    private final MailProviderRegistry registry;
    private final CryptoService crypto;

    // BUG-128: audit mail provider / config writes. Secrets (SMTP password,
    // SendGrid key etc.) land here encrypted — the audit row records WHO
    // changed WHICH provider WHEN, never the secret value itself.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.service.AuditService audit;
    // AuditService doesn't carry a MAIL_PROVIDER constant; CLIENT_POLICY is
    // the closest platform-scoped bucket. Logs page filter can target by
    // entityKey prefix "mail/" to find them.
    private static final String MAIL_ENTITY_TYPE = com.multiship.backend.service.AuditService.CLIENT_POLICY;

    public List<MailProviderEntity> listProviders() {
        return providerRepo.findAll();
    }

    public Optional<MailProviderEntity> activeProvider() {
        return providerRepo.findFirstByIsActiveTrue();
    }

    public Optional<MailProviderEntity> findById(Long id) {
        return providerRepo.findById(id);
    }

    /** Registered kinds (SMTP, SENDGRID, ...) — used by the admin FE dropdown. */
    public List<String> knownKinds() {
        return registry.kinds();
    }

    /**
     * Config keys the given kind requires + which are secrets. Returned so the
     * FE renders the correct form without duplicating the schema client-side.
     */
    public Map<String, Object> kindDescriptor(String kind) {
        MailProvider p = registry.require(kind);
        Map<String, Object> out = new HashMap<>();
        out.put("kind", p.kind());
        out.put("requiredKeys", p.requiredConfigKeys());
        out.put("secretKeys", p.secretConfigKeys());
        return out;
    }

    /**
     * Upsert a provider row. If {@code id} is null → create; else update the
     * kind/displayName (never move the primary key).
     */
    @Transactional
    public MailProviderEntity upsertProvider(Long id, String kind, String displayName, String actor) {
        registry.require(kind);  // fail-fast on unknown kind
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MailProviderEntity row;
        if (id == null) {
            row = MailProviderEntity.builder()
                    .kind(kind)
                    .displayName(displayName)
                    .isActive(false)
                    .createdAt(now)
                    .updatedAt(now)
                    .updatedBy(actor)
                    .build();
        } else {
            row = providerRepo.findById(id).orElseThrow(() ->
                    new IllegalArgumentException("mail_provider not found: " + id));
            row.setKind(kind);
            row.setDisplayName(displayName);
            row.setUpdatedAt(now);
            row.setUpdatedBy(actor);
        }
        boolean isCreate = id == null;
        MailProviderEntity saved = providerRepo.save(row);
        if (audit != null) {
            audit.record(isCreate ? com.multiship.backend.service.AuditService.CREATE
                            : com.multiship.backend.service.AuditService.UPDATE,
                    MAIL_ENTITY_TYPE, saved.getId(),
                    "mail/" + saved.getKind() + "/" + saved.getDisplayName(),
                    null, isCreate ? "Mail provider created." : "Mail provider updated.");
        }
        return saved;
    }

    /**
     * Activate one provider (deactivating any currently-active row inside the
     * same transaction). Returns the now-active row.
     */
    @Transactional
    public MailProviderEntity activate(Long providerId, String actor) {
        MailProviderEntity target = providerRepo.findById(providerId).orElseThrow(() ->
                new IllegalArgumentException("mail_provider not found: " + providerId));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        providerRepo.findFirstByIsActiveTrue().ifPresent(current -> {
            if (!current.getId().equals(providerId)) {
                current.setActive(false);
                current.setUpdatedAt(now);
                current.setUpdatedBy(actor);
                providerRepo.save(current);
            }
        });
        target.setActive(true);
        target.setUpdatedAt(now);
        target.setUpdatedBy(actor);
        MailProviderEntity saved = providerRepo.save(target);
        if (audit != null) {
            audit.record(com.multiship.backend.service.AuditService.TOGGLE_ACTIVE,
                    MAIL_ENTITY_TYPE, saved.getId(),
                    "mail/" + saved.getKind() + "/" + saved.getDisplayName(), null,
                    "Mail provider activated.");
        }
        return saved;
    }

    @Transactional
    public void deleteProvider(Long providerId) {
        // ON DELETE CASCADE on mail_config handles children.
        MailProviderEntity existing = providerRepo.findById(providerId).orElse(null);
        providerRepo.deleteById(providerId);
        if (audit != null && existing != null) {
            audit.record(com.multiship.backend.service.AuditService.DELETE,
                    MAIL_ENTITY_TYPE, providerId,
                    "mail/" + existing.getKind() + "/" + existing.getDisplayName(), null,
                    "Mail provider deleted.");
        }
    }

    // ────────────────────────── config KV ────────────────────────────

    /**
     * Full config map for a provider, secrets decrypted. Used ONLY by
     * {@link ConfiguredMailSender} on the send path — never returned to a
     * client (secrets stay server-side).
     */
    public Map<String, String> loadConfig(Long providerId) {
        Map<String, String> out = new HashMap<>();
        for (MailConfigEntity row : configRepo.findAllByProviderId(providerId)) {
            String value = row.isSecret()
                    ? (crypto.isAvailable() ? crypto.decrypt(row.getEncryptedValue()) : row.getEncryptedValue())
                    : row.getConfigValue();
            out.put(row.getConfigKey(), value);
        }
        return out;
    }

    /**
     * Public (secrets-redacted) config map for the admin FE. Secret values are
     * returned as a fixed sentinel so the operator sees "yes, a value is set"
     * without leaking the value itself.
     */
    public Map<String, String> loadConfigRedacted(Long providerId) {
        Map<String, String> out = new HashMap<>();
        for (MailConfigEntity row : configRepo.findAllByProviderId(providerId)) {
            out.put(row.getConfigKey(),
                    row.isSecret() ? "•••" : row.getConfigValue());
        }
        return out;
    }

    @Transactional
    public void putConfig(Long providerId, String key, String value, boolean isSecret, String actor) {
        if (providerId == null || key == null || key.isBlank()) {
            throw new IllegalArgumentException("providerId + key required");
        }
        if (value == null) {
            // Delete-by-null convention (matches ExternalSystemConfigService).
            configRepo.findByProviderIdAndConfigKey(providerId, key).ifPresent(configRepo::delete);
            return;
        }
        MailConfigEntity row = configRepo.findByProviderIdAndConfigKey(providerId, key)
                .orElseGet(() -> MailConfigEntity.builder()
                        .providerId(providerId)
                        .configKey(key)
                        .isSecret(isSecret)
                        .build());
        row.setSecret(isSecret);
        if (isSecret) {
            row.setConfigValue(null);
            row.setEncryptedValue(crypto.isAvailable() ? crypto.encrypt(value) : value);
        } else {
            row.setConfigValue(value);
            row.setEncryptedValue(null);
        }
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setUpdatedBy(actor);
        configRepo.save(row);
    }
}
