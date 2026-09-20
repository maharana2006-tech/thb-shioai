package com.multiship.backend.service;

import com.multiship.backend.model.TenantSetting;
import com.multiship.backend.repository.TenantSettingRepository;
import com.multiship.backend.service.TenantSettingsService.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TenantSettingsService}. Covers the generic
 * get/put/list path plus the typed {@code enabledChannels} helpers
 * (parsing + normalisation + empty-set rejection).
 *
 * <p>Anti-fallback: repository is mocked — no real DB, no accidental
 * H2 fallback. Every setup uses stubbed returns; assertions check
 * both return values AND the {@code save()} argument shape.
 */
class TenantSettingsServiceTest {

    private TenantSettingRepository repo;
    private TenantSettingsService svc;

    @BeforeEach
    void setUp() {
        repo = mock(TenantSettingRepository.class);
        svc = new TenantSettingsService(repo);
    }

    // ────────────────────────────────────────────────────────────────
    // Channel.parse
    // ────────────────────────────────────────────────────────────────

    @Test
    void channelParseAcceptsCaseInsensitive() {
        assertEquals(Optional.of(Channel.D2C), Channel.parse("d2c"));
        assertEquals(Optional.of(Channel.D2C), Channel.parse(" D2C "));
        assertEquals(Optional.of(Channel.B2B), Channel.parse("B2B"));
    }

    @Test
    void channelParseReturnsEmptyForUnknownOrBlank() {
        assertTrue(Channel.parse(null).isEmpty());
        assertTrue(Channel.parse("").isEmpty());
        assertTrue(Channel.parse("   ").isEmpty());
        assertTrue(Channel.parse("HYBRID").isEmpty());
    }

    // ────────────────────────────────────────────────────────────────
    // getSetting / listSettings
    // ────────────────────────────────────────────────────────────────

    @Test
    void getSettingIsEmptyForNullOrBlankArgs() {
        assertTrue(svc.getSetting(null, "k").isEmpty());
        assertTrue(svc.getSetting("", "k").isEmpty());
        assertTrue(svc.getSetting("t", null).isEmpty());
        assertTrue(svc.getSetting("t", "").isEmpty());
        verify(repo, never()).findByTenantCodeAndSettingKey(any(), any());
    }

    @Test
    void getSettingReturnsStoredValue() {
        TenantSetting row = new TenantSetting();
        row.setSettingValue("hello");
        when(repo.findByTenantCodeAndSettingKey("THB000", "greeting"))
                .thenReturn(Optional.of(row));
        assertEquals(Optional.of("hello"), svc.getSetting("THB000", "greeting"));
    }

    @Test
    void listSettingsReturnsSortedByKey() {
        TenantSetting a = new TenantSetting(); a.setSettingKey("beta"); a.setSettingValue("2");
        TenantSetting b = new TenantSetting(); b.setSettingKey("alpha"); b.setSettingValue("1");
        when(repo.findByTenantCode("THB000")).thenReturn(List.of(a, b));

        List<TenantSetting> sorted = svc.listSettings("THB000");
        assertEquals(List.of("alpha", "beta"),
                sorted.stream().map(TenantSetting::getSettingKey).toList());
    }

    // ────────────────────────────────────────────────────────────────
    // putSetting
    // ────────────────────────────────────────────────────────────────

    @Test
    void putSettingCreatesFreshRowWhenAbsent() {
        when(repo.findByTenantCodeAndSettingKey("THB000", "key1")).thenReturn(Optional.empty());
        when(repo.save(any(TenantSetting.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.putSetting("THB000", "key1", "val", "alice");

        ArgumentCaptor<TenantSetting> cap = ArgumentCaptor.forClass(TenantSetting.class);
        verify(repo).save(cap.capture());
        assertEquals("THB000", cap.getValue().getTenantCode());
        assertEquals("key1", cap.getValue().getSettingKey());
        assertEquals("val", cap.getValue().getSettingValue());
        assertEquals("alice", cap.getValue().getUpdatedBy());
    }

    @Test
    void putSettingUpdatesExistingRowPreservingId() {
        TenantSetting existing = new TenantSetting();
        existing.setId(42L);
        existing.setTenantCode("THB000");
        existing.setSettingKey("key1");
        existing.setSettingValue("old");
        when(repo.findByTenantCodeAndSettingKey("THB000", "key1")).thenReturn(Optional.of(existing));
        when(repo.save(any(TenantSetting.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.putSetting("THB000", "key1", "new", "bob");

        ArgumentCaptor<TenantSetting> cap = ArgumentCaptor.forClass(TenantSetting.class);
        verify(repo).save(cap.capture());
        assertEquals(42L, cap.getValue().getId(), "id must be preserved on upsert");
        assertEquals("new", cap.getValue().getSettingValue());
        assertEquals("bob", cap.getValue().getUpdatedBy());
    }

    @Test
    void putSettingThrowsForBlankInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.putSetting(null, "k", "v", "u"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.putSetting("", "k", "v", "u"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.putSetting("t", null, "v", "u"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.putSetting("t", "k", null, "u"));
        verify(repo, never()).save(any());
    }

    // ────────────────────────────────────────────────────────────────
    // getEnabledChannels — parsing + missing/corrupted rows
    // ────────────────────────────────────────────────────────────────

    @Test
    void getEnabledChannelsReturnsEmptyWhenRowAbsent() {
        when(repo.findByTenantCodeAndSettingKey("THB000", "enabledChannels"))
                .thenReturn(Optional.empty());
        assertTrue(svc.getEnabledChannels("THB000").isEmpty(),
                "Missing setting = force-picking default (caller decides how to interpret).");
    }

    @Test
    void getEnabledChannelsParsesCommaSeparatedValues() {
        TenantSetting row = new TenantSetting();
        row.setSettingValue("D2C,B2B");
        when(repo.findByTenantCodeAndSettingKey("THB000", "enabledChannels"))
                .thenReturn(Optional.of(row));
        assertEquals(EnumSet.of(Channel.D2C, Channel.B2B),
                svc.getEnabledChannels("THB000"));
    }

    @Test
    void getEnabledChannelsTolerantsSingleValue() {
        TenantSetting row = new TenantSetting();
        row.setSettingValue("B2B");
        when(repo.findByTenantCodeAndSettingKey("THB000", "enabledChannels"))
                .thenReturn(Optional.of(row));
        assertEquals(EnumSet.of(Channel.B2B), svc.getEnabledChannels("THB000"));
    }

    @Test
    void getEnabledChannelsSkipsUnknownTokens() {
        // Manual SQL write with a typo — silent skip so a corrupted row
        // doesn't throw on the hot path (guard already returns 403 for
        // empty set; better than 500).
        TenantSetting row = new TenantSetting();
        row.setSettingValue("D2C,HYBRID,B2B,,");
        when(repo.findByTenantCodeAndSettingKey("THB000", "enabledChannels"))
                .thenReturn(Optional.of(row));
        assertEquals(EnumSet.of(Channel.D2C, Channel.B2B),
                svc.getEnabledChannels("THB000"));
    }

    // ────────────────────────────────────────────────────────────────
    // setEnabledChannels — normalisation + empty rejection
    // ────────────────────────────────────────────────────────────────

    @Test
    void setEnabledChannelsThrowsForEmptySet() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.setEnabledChannels("THB000", EnumSet.noneOf(Channel.class), "actor"),
                "Empty selection is a misconfiguration; caller must pick at least one.");
        assertThrows(IllegalArgumentException.class,
                () -> svc.setEnabledChannels("THB000", null, "actor"));
        verify(repo, never()).save(any());
    }

    @Test
    void setEnabledChannelsWritesAlphabeticallySorted() {
        // Input in D2C-then-B2B order, expect stored as B2B,D2C
        // (alphabetical) so the row is deterministic.
        when(repo.findByTenantCodeAndSettingKey(any(), any())).thenReturn(Optional.empty());
        when(repo.save(any(TenantSetting.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.setEnabledChannels("THB000", EnumSet.of(Channel.D2C, Channel.B2B), "admin");

        ArgumentCaptor<TenantSetting> cap = ArgumentCaptor.forClass(TenantSetting.class);
        verify(repo).save(cap.capture());
        assertEquals("B2B,D2C", cap.getValue().getSettingValue(),
                "stored value must be alphabetically ordered for determinism");
    }

    @Test
    void setEnabledChannelsSingleChannelRoundtrips() {
        when(repo.findByTenantCodeAndSettingKey(any(), any())).thenReturn(Optional.empty());
        when(repo.save(any(TenantSetting.class))).thenAnswer(inv -> {
            TenantSetting arg = inv.getArgument(0);
            // Simulate the DB read after write.
            when(repo.findByTenantCodeAndSettingKey("THB000", "enabledChannels"))
                    .thenReturn(Optional.of(arg));
            return arg;
        });

        svc.setEnabledChannels("THB000", EnumSet.of(Channel.D2C), "admin");
        assertEquals(EnumSet.of(Channel.D2C), svc.getEnabledChannels("THB000"));
    }
}
