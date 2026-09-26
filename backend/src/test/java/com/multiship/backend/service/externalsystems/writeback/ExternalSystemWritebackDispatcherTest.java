package com.multiship.backend.service.externalsystems.writeback;

import com.multiship.backend.model.ExternalSystemConnection;
import com.multiship.backend.service.TenantSettingsService;
import com.multiship.backend.service.externalsystems.ExternalSystemConfigService;
import com.multiship.backend.service.externalsystems.ExternalSystemRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * V89 — dispatcher tests. Focus: field redaction respects the flag
 * matrix, connection resolution prefers the tenant-setting override,
 * and a null-payload / no-connector / missing-connection call is a
 * silent no-op (never propagates back to the label caller).
 */
class ExternalSystemWritebackDispatcherTest {

    private ExternalSystemRegistry registry;
    private ExternalSystemConfigService config;
    private TenantSettingsService tenantSettings;
    private ExternalSystemWritebackDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        registry = mock(ExternalSystemRegistry.class);
        config = mock(ExternalSystemConfigService.class);
        tenantSettings = mock(TenantSettingsService.class);
        dispatcher = new ExternalSystemWritebackDispatcher(registry, config, tenantSettings);
    }

    private static ExternalSystemConnection conn(String name, boolean tr, boolean sd,
                                                  boolean st, boolean ca, boolean sv, boolean fr) {
        ExternalSystemConnection c = new ExternalSystemConnection();
        c.setId(1L);
        c.setName(name);
        c.setActive(true);
        c.setSystemType("REST_JSON");
        c.setWritebackTracking(tr);
        c.setWritebackShipDate(sd);
        c.setWritebackStatus(st);
        c.setWritebackCarrier(ca);
        c.setWritebackService(sv);
        c.setWritebackFreight(fr);
        return c;
    }

    private static WritebackPayload fullPayload() {
        return WritebackPayload.builder()
                .clientCode("ACME")
                .orderNo(1001)
                .trackingNumber("1Z999")
                .shipDate(LocalDateTime.of(2026, 1, 1, 0, 0))
                .status("SHIPPED")
                .carrierCode("UPS")
                .serviceCode("UPS_02")
                .freightAmount(new BigDecimal("12.34"))
                .currency("USD")
                .build();
    }

    // ── field redaction ────────────────────────────────────────────

    @Test
    void redactByFlagsKeepsOnlyFlaggedFields() {
        ExternalSystemConnection row = conn("nds-default", true, false, true, false, false, true);
        WritebackPayload redacted = dispatcher.redactByFlags(fullPayload(), row);
        assertEquals("1Z999", redacted.trackingNumber());
        assertNull(redacted.shipDate());
        assertEquals("SHIPPED", redacted.status());
        assertNull(redacted.carrierCode());
        assertNull(redacted.serviceCode());
        assertEquals(new BigDecimal("12.34"), redacted.freightAmount());
        // Currency is coupled to freight: when freight is flagged, currency comes along.
        assertEquals("USD", redacted.currency());
    }

    @Test
    void redactByFlagsAllFalseYieldsAllNulls() {
        ExternalSystemConnection row = conn("nds-default", false, false, false, false, false, false);
        WritebackPayload redacted = dispatcher.redactByFlags(fullPayload(), row);
        assertTrue(ExternalSystemWritebackDispatcher.allFieldsRedacted(redacted));
    }

    @Test
    void redactByFlagsAllTrueKeepsAllFields() {
        ExternalSystemConnection row = conn("nds-default", true, true, true, true, true, true);
        WritebackPayload redacted = dispatcher.redactByFlags(fullPayload(), row);
        assertEquals("1Z999", redacted.trackingNumber());
        assertNotNull(redacted.shipDate());
        assertEquals("SHIPPED", redacted.status());
        assertEquals("UPS", redacted.carrierCode());
        assertEquals("UPS_02", redacted.serviceCode());
        assertEquals(new BigDecimal("12.34"), redacted.freightAmount());
    }

    // ── connection resolution ──────────────────────────────────────

    @Test
    void resolveConnectionNamePrefersTenantSettingOverDefault() {
        when(tenantSettings.getSetting("ACME", ExternalSystemWritebackDispatcher.SETTING_WRITEBACK_CONNECTION))
                .thenReturn(Optional.of("acme-sap"));
        String name = dispatcher.resolveConnectionName("ACME");
        assertEquals("acme-sap", name);
        // Default lookup NOT consulted — save a DB hit on the hot path.
        verify(config, never()).findByName(anyString());
    }

    @Test
    void resolveConnectionNameFallsBackToDefaultWhenNoTenantSetting() {
        when(tenantSettings.getSetting(anyString(), anyString())).thenReturn(Optional.empty());
        ExternalSystemConnection def = conn("nds-default", true, true, true, true, true, true);
        when(config.findByName("nds-default")).thenReturn(Optional.of(def));
        assertEquals("nds-default", dispatcher.resolveConnectionName("ACME"));
    }

    @Test
    void resolveConnectionNameReturnsNullWhenNothingWired() {
        when(tenantSettings.getSetting(anyString(), anyString())).thenReturn(Optional.empty());
        when(config.findByName(anyString())).thenReturn(Optional.empty());
        assertNull(dispatcher.resolveConnectionName("ACME"));
        assertNull(dispatcher.resolveConnectionName(""));
        assertNull(dispatcher.resolveConnectionName(null));
    }

    // ── dispatch integration ───────────────────────────────────────

    @Test
    void dispatchOnGenerateSkipsWhenAllFieldsRedacted() {
        when(tenantSettings.getSetting(anyString(), anyString())).thenReturn(Optional.empty());
        ExternalSystemConnection row = conn("nds-default", false, false, false, false, false, false);
        when(config.findByName("nds-default")).thenReturn(Optional.of(row));

        dispatcher.dispatchOnGenerate(fullPayload());

        verify(registry, never()).writeShipment(anyString(), any());
    }

    @Test
    void dispatchOnGenerateCallsRegistryWhenAtLeastOneFlagOn() {
        when(tenantSettings.getSetting(anyString(), anyString())).thenReturn(Optional.empty());
        ExternalSystemConnection row = conn("nds-default", true, false, false, false, false, false);
        when(config.findByName("nds-default")).thenReturn(Optional.of(row));
        when(registry.writeShipment(eq("nds-default"), any())).thenReturn(WritebackAck.ok("done"));

        dispatcher.dispatchOnGenerate(fullPayload());

        verify(registry).writeShipment(eq("nds-default"), any(WritebackPayload.class));
    }

    @Test
    void dispatchOnGenerateSwallowsNullPayload() {
        assertDoesNotThrow(() -> dispatcher.dispatchOnGenerate(null));
        verifyNoInteractions(registry);
    }

    @Test
    void dispatchOnGenerateSwallowsRegistryException() {
        when(tenantSettings.getSetting(anyString(), anyString())).thenReturn(Optional.empty());
        ExternalSystemConnection row = conn("nds-default", true, true, true, true, true, true);
        when(config.findByName("nds-default")).thenReturn(Optional.of(row));
        when(registry.writeShipment(anyString(), any()))
                .thenThrow(new RuntimeException("registry blew up"));

        // The dispatcher's @Async method is a plain call in this unit test
        // (no Spring proxy), so the exception WOULD propagate if not caught.
        // Ponytail rule: fire-and-forget must not bubble.
        assertDoesNotThrow(() -> dispatcher.dispatchOnGenerate(fullPayload()));
    }

    @Test
    void dispatchOnClearSkipsWhenNoFlagsEnabled() {
        when(tenantSettings.getSetting(anyString(), anyString())).thenReturn(Optional.empty());
        ExternalSystemConnection row = conn("nds-default", false, false, false, false, false, false);
        when(config.findByName("nds-default")).thenReturn(Optional.of(row));

        dispatcher.dispatchOnClear(WritebackClearRequest.of(null, "1Z999", 1001, "ACME"));

        verify(registry, never()).clearShipment(anyString(), any());
    }

    @Test
    void dispatchOnClearForwardsFlagsToRegistry() {
        when(tenantSettings.getSetting(anyString(), anyString())).thenReturn(Optional.empty());
        ExternalSystemConnection row = conn("nds-default", true, false, true, false, false, true);
        when(config.findByName("nds-default")).thenReturn(Optional.of(row));
        when(registry.clearShipment(anyString(), any())).thenReturn(WritebackAck.ok("cleared"));

        dispatcher.dispatchOnClear(WritebackClearRequest.of(null, "1Z999", 1001, "ACME"));

        var captor = org.mockito.ArgumentCaptor.forClass(WritebackClearRequest.class);
        verify(registry).clearShipment(eq("nds-default"), captor.capture());
        WritebackClearRequest sent = captor.getValue();
        assertTrue(sent.clearTracking());
        assertFalse(sent.clearShipDate());
        assertTrue(sent.clearStatus());
        assertFalse(sent.clearCarrier());
        assertFalse(sent.clearService());
        assertTrue(sent.clearFreight());
    }
}
