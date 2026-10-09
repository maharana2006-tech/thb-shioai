package com.multiship.backend.service.externalsystems.writeback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.InactiveOracleDataSource;
import com.multiship.backend.model.ExternalSystemConnection;
import com.multiship.backend.service.TenantSettingsService;
import com.multiship.backend.service.externalsystems.ExternalSystemConfigService;
import com.multiship.backend.service.externalsystems.ExternalSystemRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** PR-I5 — pins the inactive-connection contract across the three
 *  surfaces I1-I4 shipped:
 *  <ul>
 *    <li>Startup: {@link InactiveOracleDataSource#getConnection}
 *        throws a clear "connection inactive" message with the
 *        connection name in it.</li>
 *    <li>Writeback dispatcher: generate + clear journal a
 *        {@code SKIPPED_INACTIVE} row and skip the connector call.</li>
 *    <li>Writeback probe: covered by a controller-level 422 check in
 *        {@code ExternalSystemsAdminControllerTest} (admin endpoint
 *        test), not here — this file stays on dispatcher internals.</li>
 *  </ul>
 */
class InactiveConnectionWritebackTest {

    private ExternalSystemRegistry registry;
    private ExternalSystemConfigService config;
    private TenantSettingsService tenantSettings;
    private WritebackJournalService journal;
    private ExternalSystemWritebackDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        registry = mock(ExternalSystemRegistry.class);
        config = mock(ExternalSystemConfigService.class);
        tenantSettings = mock(TenantSettingsService.class);
        journal = mock(WritebackJournalService.class);
        dispatcher = new ExternalSystemWritebackDispatcher(
                registry, config, tenantSettings, journal, new ObjectMapper());
    }

    // ===== InactiveOracleDataSource stub =====

    @Test
    void inactiveDataSource_throwsOnGetConnection_withConnectionNameInMessage() {
        InactiveOracleDataSource ds = new InactiveOracleDataSource("nds-default");
        IllegalStateException ex = assertThrows(IllegalStateException.class, ds::getConnection);
        assertTrue(ex.getMessage().contains("nds-default"),
                "message must name the inactive connection so stray callers can diagnose: " + ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("inactive"),
                "message must say 'inactive': " + ex.getMessage());
    }

    @Test
    void inactiveDataSource_twoArgOverloadThrowsSameWay() {
        InactiveOracleDataSource ds = new InactiveOracleDataSource("nds-default");
        assertThrows(IllegalStateException.class, () -> ds.getConnection("u", "p"));
    }

    @Test
    void inactiveDataSource_metadataMethodsDoNotCrash() throws SQLException {
        InactiveOracleDataSource ds = new InactiveOracleDataSource("nds-default");
        // Hibernate's EMF bootstrap probes these at context-init time;
        // they must be no-op-safe, not throw.
        assertDoesNotThrow(() -> ds.setLoginTimeout(5));
        assertEquals(0, ds.getLoginTimeout());
        assertNull(ds.getLogWriter());
        assertFalse(ds.isWrapperFor(String.class));
    }

    // ===== Dispatcher: inactive-generate =====

    @Test
    void dispatchOnGenerate_inactiveConnection_journalsSkippedInactive_noConnectorCall() {
        WritebackPayload payload = WritebackPayload.builder()
                .clientCode("ACME").orderNo(777).trackingNumber("TRK-1")
                .source("MANUAL").channel("D2C").status("SHIPPED")
                .build();
        when(tenantSettings.getSetting("ACME",
                ExternalSystemWritebackDispatcher.SETTING_WRITEBACK_CONNECTION))
                .thenReturn(Optional.of("nds-default"));
        when(config.findByName("nds-default"))
                .thenReturn(Optional.of(inactiveRow("nds-default", "NDS_ORACLE")));

        dispatcher.dispatchOnGenerate(payload);

        verify(journal, times(1)).recordSkippedInactive(
                eq("nds-default"), eq("NDS_ORACLE"),
                eq(WritebackJournalService.MODE_GENERATE), eq(payload));
        verify(journal, never()).recordPending(anyString(), anyString(),
                anyString(), any(WritebackPayload.class), any());
        verify(registry, never()).writeShipment(anyString(), any());
    }

    // ===== Dispatcher: inactive-clear =====

    @Test
    void dispatchOnClear_inactiveConnection_journalsSkippedInactive_noConnectorCall() {
        WritebackClearRequest req = WritebackClearRequest.of(null, "TRK-1", 777,
                "ACME", "MANUAL", "D2C");
        when(tenantSettings.getSetting("ACME",
                ExternalSystemWritebackDispatcher.SETTING_WRITEBACK_CONNECTION))
                .thenReturn(Optional.of("nds-default"));
        when(config.findByName("nds-default"))
                .thenReturn(Optional.of(inactiveRow("nds-default", "NDS_ORACLE")));

        dispatcher.dispatchOnClear(req);

        verify(journal, times(1)).recordSkippedInactive(
                eq("nds-default"), eq("NDS_ORACLE"), any(WritebackClearRequest.class));
        verify(registry, never()).clearShipment(anyString(), any());
    }

    // ===== Dispatcher: active-generate still works =====

    @Test
    void dispatchOnGenerate_activeConnection_doesNotTakeInactiveBranch() {
        WritebackPayload payload = WritebackPayload.builder()
                .clientCode("ACME").orderNo(777).trackingNumber("TRK-1")
                .source("MANUAL").channel("D2C").status("SHIPPED")
                .carrierCode("USPS")
                .build();
        ExternalSystemConnection row = activeRowWithAllFlags("nds-default", "NDS_ORACLE");
        when(tenantSettings.getSetting("ACME",
                ExternalSystemWritebackDispatcher.SETTING_WRITEBACK_CONNECTION))
                .thenReturn(Optional.of("nds-default"));
        when(config.findByName("nds-default")).thenReturn(Optional.of(row));
        when(journal.recordPending(anyString(), anyString(),
                anyString(), any(WritebackPayload.class), any()))
                .thenReturn(com.multiship.backend.model.WritebackJournalEntity.builder()
                        .id(1L).build());
        when(registry.writeShipment(eq("nds-default"), any()))
                .thenReturn(new WritebackAck(WritebackAck.Status.OK, "ok"));

        dispatcher.dispatchOnGenerate(payload);

        // The inactive branch must NOT fire; the live journal path does.
        verify(journal, never()).recordSkippedInactive(anyString(), anyString(),
                anyString(), any(WritebackPayload.class));
        verify(registry, times(1)).writeShipment(eq("nds-default"), any());
    }

    // ===== helpers =====

    private static ExternalSystemConnection inactiveRow(String name, String systemType) {
        ExternalSystemConnection r = new ExternalSystemConnection();
        r.setId(42L); r.setName(name); r.setSystemType(systemType); r.setActive(false);
        return r;
    }

    private static ExternalSystemConnection activeRowWithAllFlags(String name, String systemType) {
        ExternalSystemConnection r = new ExternalSystemConnection();
        r.setId(42L); r.setName(name); r.setSystemType(systemType); r.setActive(true);
        r.setWritebackTracking(true); r.setWritebackShipDate(true);
        r.setWritebackStatus(true); r.setWritebackCarrier(true);
        r.setWritebackService(true); r.setWritebackFreight(true);
        r.setWritebackSourceManual(true); r.setWritebackChannelD2c(true);
        return r;
    }
}
