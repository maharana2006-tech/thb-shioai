package com.multiship.backend.service;

import com.multiship.backend.model.TenantSetting;
import com.multiship.backend.repository.TenantSettingRepository;
import com.multiship.backend.service.TenantSettingsService.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantChannelGuard} — the intake gate that
 * enforces the per-tenant D2C/B2B allowlist. Uses the real
 * {@link TenantSettingsService} with a mocked repository so the
 * parse/normalise logic is exercised end-to-end but no DB is touched.
 */
class TenantChannelGuardTest {

    private TenantSettingRepository repo;
    private TenantSettingsService settings;
    private TenantChannelGuard guard;

    @BeforeEach
    void setUp() {
        repo = mock(TenantSettingRepository.class);
        settings = new TenantSettingsService(repo);
        guard = new TenantChannelGuard(settings);
    }

    // ────────────────────────────────────────────────────────────────
    // classify (static helper, mirrors CarrierServiceImpl precedence)
    // ────────────────────────────────────────────────────────────────

    @Test
    void classifyExplicitChannelWinsOverAutoDetection() {
        // Even with company present (would auto-classify as B2B), an
        // explicit D2C on the request payload takes precedence.
        assertEquals(Channel.D2C,
                TenantChannelGuard.classify("D2C", null, "Acme Inc"));
    }

    @Test
    void classifyIgnoresUnparseableExplicitAndFallsThrough() {
        // Unknown token → skip to auto-detection.
        assertEquals(Channel.B2B,
                TenantChannelGuard.classify("HYBRID", null, "Acme Inc"));
    }

    @Test
    void classifyResidentialTrueOverridesCompanyPresence() {
        // A home business is still a residential (carrier rating).
        assertEquals(Channel.D2C,
                TenantChannelGuard.classify(null, Boolean.TRUE, "Acme Inc"));
    }

    @Test
    void classifyCompanyPresenceAutoDetectsB2B() {
        assertEquals(Channel.B2B,
                TenantChannelGuard.classify(null, null, "Acme Inc"));
    }

    @Test
    void classifyDefaultsToD2CWhenNothingProvided() {
        assertEquals(Channel.D2C,
                TenantChannelGuard.classify(null, null, null));
        assertEquals(Channel.D2C,
                TenantChannelGuard.classify(null, Boolean.FALSE, ""));
    }

    // ────────────────────────────────────────────────────────────────
    // requireConfigured (WMS force-picking gate)
    // ────────────────────────────────────────────────────────────────

    @Test
    void requireConfiguredThrowsWhenNoSetting() {
        when(repo.findByTenantCodeAndSettingKey(anyString(), any())).thenReturn(Optional.empty());
        TenantChannelGuard.ChannelNotEnabledException e = assertThrows(
                TenantChannelGuard.ChannelNotEnabledException.class,
                () -> guard.requireConfigured("THB000"));
        assertTrue(e.getMessage().contains("/settings/system"),
                "message must point operator at the configuration page");
    }

    @Test
    void requireConfiguredPassesWhenAtLeastOneChannelEnabled() {
        TenantSetting row = new TenantSetting();
        row.setSettingValue("B2B");
        when(repo.findByTenantCodeAndSettingKey("THB000", "enabledChannels"))
                .thenReturn(Optional.of(row));
        assertDoesNotThrow(() -> guard.requireConfigured("THB000"));
    }

    @Test
    void requireConfiguredThrowsForNullOrBlankTenant() {
        assertThrows(TenantChannelGuard.ChannelNotEnabledException.class,
                () -> guard.requireConfigured(null));
        assertThrows(TenantChannelGuard.ChannelNotEnabledException.class,
                () -> guard.requireConfigured(""));
    }

    // ────────────────────────────────────────────────────────────────
    // requireChannel (external-API per-order gate)
    // ────────────────────────────────────────────────────────────────

    @Test
    void requireChannelThrowsWhenSettingAbsent() {
        when(repo.findByTenantCodeAndSettingKey(anyString(), any())).thenReturn(Optional.empty());
        TenantChannelGuard.ChannelNotEnabledException e = assertThrows(
                TenantChannelGuard.ChannelNotEnabledException.class,
                () -> guard.requireChannel("THB000", Channel.D2C));
        assertTrue(e.getMessage().contains("/settings/system"));
    }

    @Test
    void requireChannelThrowsWhenChannelNotInAllowlist() {
        TenantSetting row = new TenantSetting();
        row.setSettingValue("D2C"); // only D2C enabled
        when(repo.findByTenantCodeAndSettingKey("THB000", "enabledChannels"))
                .thenReturn(Optional.of(row));

        TenantChannelGuard.ChannelNotEnabledException e = assertThrows(
                TenantChannelGuard.ChannelNotEnabledException.class,
                () -> guard.requireChannel("THB000", Channel.B2B));
        assertTrue(e.getMessage().contains("B2B"),
                "message names the rejected channel");
        assertTrue(e.getMessage().contains("D2C"),
                "message includes the tenant's enabled list");
    }

    @Test
    void requireChannelPassesWhenChannelInAllowlist() {
        TenantSetting row = new TenantSetting();
        row.setSettingValue("B2B,D2C");
        when(repo.findByTenantCodeAndSettingKey("THB000", "enabledChannels"))
                .thenReturn(Optional.of(row));

        assertDoesNotThrow(() -> guard.requireChannel("THB000", Channel.D2C));
        assertDoesNotThrow(() -> guard.requireChannel("THB000", Channel.B2B));
    }

    @Test
    void requireChannelThrowsWhenChannelIsNull() {
        // Null channel = unclassifiable order — treat as rejection so
        // corrupted upstream data doesn't quietly bypass the gate.
        TenantSetting row = new TenantSetting();
        row.setSettingValue("D2C,B2B");
        when(repo.findByTenantCodeAndSettingKey("THB000", "enabledChannels"))
                .thenReturn(Optional.of(row));

        TenantChannelGuard.ChannelNotEnabledException e = assertThrows(
                TenantChannelGuard.ChannelNotEnabledException.class,
                () -> guard.requireChannel("THB000", null));
        assertTrue(e.getMessage().contains("unclassified"));
    }

    // ────────────────────────────────────────────────────────────────
    // ChannelNotEnabledException carries the ErrorCode
    // ────────────────────────────────────────────────────────────────

    @Test
    void exceptionCarriesTenantChannelNotEnabledErrorCode() {
        TenantChannelGuard.ChannelNotEnabledException e =
                new TenantChannelGuard.ChannelNotEnabledException("test");
        assertEquals("TENANT_CHANNEL_NOT_ENABLED", e.errorCode().name());
        // Must extend AccessDeniedException so Spring Security's default
        // filter chain maps it to 403 (even outside our wrapper).
        assertTrue(e instanceof org.springframework.security.access.AccessDeniedException);
    }

    @Test
    void enabledChannelsListEnumSetIsReturnedInStableOrder() {
        // Sanity check: the underlying service normalises on write, so a
        // random-order write comes back deterministic.
        TenantSetting row = new TenantSetting();
        // Simulate what the service writes (alphabetical CSV).
        row.setSettingValue("B2B,D2C");
        when(repo.findByTenantCodeAndSettingKey("THB000", "enabledChannels"))
                .thenReturn(Optional.of(row));

        EnumSet<Channel> got = settings.getEnabledChannels("THB000");
        assertEquals(EnumSet.of(Channel.B2B, Channel.D2C), got);
    }
}
