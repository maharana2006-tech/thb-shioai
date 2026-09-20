package com.multiship.backend.service;

import com.multiship.backend.model.TenantSetting;
import com.multiship.backend.repository.TenantSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read/write per-tenant preferences backed by the {@code tenant_settings}
 * table. Exposes generic {@link #getSetting} / {@link #putSetting} plus
 * typed helpers for the first consumer ({@code enabledChannels}).
 *
 * <p>Encoding for {@code enabledChannels}: comma-separated
 * {@code D2C} and/or {@code B2B}, order-independent on the way in,
 * normalised to alphabetical ({@code B2B,D2C}) on the way out. Missing
 * setting = empty set (see {@link #getEnabledChannels}); the caller
 * decides whether that means "force pick" (external-API gate) or
 * "no restriction" (would-be permissive mode).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantSettingsService {

    /** Setting key for the B2B/D2C tenant channel-gate feature. */
    public static final String KEY_ENABLED_CHANNELS = "enabledChannels";

    /** Shipping channel enum. Must match values used on the Order /
     *  ManualShipmentRequest / ExternalShipmentRequest {@code channel}
     *  field (documented in-place as "D2C | B2B"). */
    public enum Channel {
        D2C,
        B2B;

        /** Parse a case-insensitive channel string; returns empty for
         *  null / blank / unknown so callers can decide fallback semantics. */
        public static Optional<Channel> parse(String raw) {
            if (raw == null) return Optional.empty();
            String norm = raw.trim().toUpperCase();
            if (norm.isEmpty()) return Optional.empty();
            try {
                return Optional.of(Channel.valueOf(norm));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    private final TenantSettingRepository repo;

    /** Read a raw setting value; empty if the row doesn't exist. */
    public Optional<String> getSetting(String tenantCode, String key) {
        if (tenantCode == null || tenantCode.isBlank() || key == null || key.isBlank()) {
            return Optional.empty();
        }
        return repo.findByTenantCodeAndSettingKey(tenantCode, key)
                .map(TenantSetting::getSettingValue);
    }

    /** All settings for a tenant, ordered by setting_key alphabetically. */
    public List<TenantSetting> listSettings(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) return List.of();
        return repo.findByTenantCode(tenantCode).stream()
                .sorted((a, b) -> a.getSettingKey().compareTo(b.getSettingKey()))
                .collect(Collectors.toList());
    }

    /** Upsert a raw setting. {@code actor} is the JWT username of the
     *  writer (null-tolerated for system-seeded writes). */
    @Transactional
    public TenantSetting putSetting(String tenantCode, String key, String value, String actor) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new IllegalArgumentException("tenantCode required");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("setting key required");
        }
        if (value == null) {
            throw new IllegalArgumentException("setting value required (use delete to remove)");
        }
        TenantSetting row = repo.findByTenantCodeAndSettingKey(tenantCode, key)
                .orElseGet(() -> {
                    TenantSetting fresh = new TenantSetting();
                    fresh.setTenantCode(tenantCode);
                    fresh.setSettingKey(key);
                    return fresh;
                });
        row.setSettingValue(value);
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setUpdatedBy(actor);
        TenantSetting saved = repo.save(row);
        log.info("tenant-setting: tenant={} key={} actor={} (id={})",
                tenantCode, key, actor, saved.getId());
        return saved;
    }

    // ────────────────────────────────────────────────────────────────
    // Typed helpers for enabledChannels
    // ────────────────────────────────────────────────────────────────

    /**
     * Enabled channels for the tenant. Empty set means the setting is
     * absent — the caller decides whether that's "force pick" (external
     * API gate) or "no restriction" (permissive default).
     *
     * <p>Unknown tokens are silently dropped so a corrupted row (manual
     * SQL write with a typo) degrades to "no valid channels" rather than
     * throwing on the hot path.
     */
    public EnumSet<Channel> getEnabledChannels(String tenantCode) {
        return getSetting(tenantCode, KEY_ENABLED_CHANNELS)
                .map(TenantSettingsService::parseChannels)
                .orElseGet(() -> EnumSet.noneOf(Channel.class));
    }

    /**
     * Persist the enabled-channels selection. The input list is
     * deduplicated + normalised to alphabetical order on write so the
     * stored string is deterministic.
     *
     * @throws IllegalArgumentException if the selection is empty (a
     *         tenant must enable at least one channel; use "both" for
     *         permissive).
     */
    @Transactional
    public TenantSetting setEnabledChannels(String tenantCode, Set<Channel> channels, String actor) {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one channel must be enabled (D2C, B2B, or both).");
        }
        String value = channels.stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(Enum::name)
                .collect(Collectors.joining(","));
        return putSetting(tenantCode, KEY_ENABLED_CHANNELS, value, actor);
    }

    // ────────────────────────────────────────────────────────────────

    private static EnumSet<Channel> parseChannels(String raw) {
        if (raw == null || raw.isBlank()) return EnumSet.noneOf(Channel.class);
        Set<Channel> collected = new LinkedHashSet<>();
        for (String token : Arrays.asList(raw.split(","))) {
            Channel.parse(token).ifPresent(collected::add);
        }
        return collected.isEmpty() ? EnumSet.noneOf(Channel.class) : EnumSet.copyOf(collected);
    }
}
