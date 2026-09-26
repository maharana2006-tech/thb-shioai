package com.multiship.backend.service.ndsshipment;

import com.multiship.backend.service.externalsystems.writeback.WritebackAck;
import com.multiship.backend.service.externalsystems.writeback.WritebackClearRequest;
import com.multiship.backend.service.externalsystems.writeback.WritebackPackagePayload;
import com.multiship.backend.service.externalsystems.writeback.WritebackPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * V89 — NdsShipmentOracleWriter unit tests. Focus: the SET clause a
 * given flag-matrix produces, and the WHERE-clause row-lookup keys.
 * Mocks JdbcTemplate — no live Oracle here.
 */
class NdsShipmentOracleWriterTest {

    private NdsTemplates templates;
    private NamedParameterJdbcTemplate jdbc;
    private NdsShipmentOracleWriter writer;

    @BeforeEach
    void setUp() {
        templates = mock(NdsTemplates.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        when(templates.forClient(anyString())).thenReturn(jdbc);
        writer = new NdsShipmentOracleWriter(templates);
    }

    // ── SET-clause building ────────────────────────────────────────

    @Test
    void buildClipperSetsOnlyIncludesNonNullFields() {
        WritebackPayload p = WritebackPayload.builder()
                .trackingNumber("1Z999")
                .carrierCode("UPS")
                // shipDate, status, service, freight all null (unflagged)
                .build();
        Map<String, Object> sets = writer.buildClipperSets(p, false);
        assertEquals(2, sets.size());
        assertEquals("1Z999", sets.get("tracking_number"));
        assertEquals("UPS", sets.get("carrier_code"));
        assertFalse(sets.containsKey("ship_date"));
        assertFalse(sets.containsKey("status"));
        assertFalse(sets.containsKey("service_code"));
        assertFalse(sets.containsKey("freight_amount"));
    }

    @Test
    void buildOeTrackingSetsMirrorsFieldNamesForNdsSchema() {
        WritebackPayload p = WritebackPayload.builder()
                .trackingNumber("1Z999")
                .shipDate(LocalDateTime.of(2026, 1, 1, 0, 0))
                .status("SHIPPED")
                .carrierCode("UPS")
                .serviceCode("UPS_02")
                .freightAmount(new BigDecimal("12.34"))
                .build();
        Map<String, Object> sets = writer.buildOeTrackingSets(p, false);
        // OE_TRACKING uses tracking_no / ship_status / carrier_cd / service_cd / freight_amt.
        assertTrue(sets.containsKey("tracking_no"));
        assertTrue(sets.containsKey("ship_status"));
        assertTrue(sets.containsKey("carrier_cd"));
        assertTrue(sets.containsKey("service_cd"));
        assertTrue(sets.containsKey("freight_amt"));
        assertTrue(sets.containsKey("ship_date"));
    }

    @Test
    void clearingSetsForClipperStampsVoidedStatusWhenStatusFlagged() {
        Map<String, Object> sets = writer.clearingSetsForClipper(
                true, true, true, true, true, true);
        assertNull(sets.get("tracking_number"));
        assertEquals("VOIDED", sets.get("status"));
        assertNull(sets.get("carrier_code"));
    }

    @Test
    void clearingSetsForClipperOnlyIncludesFlaggedColumns() {
        // Only tracking + freight flags on → only those two columns cleared.
        Map<String, Object> sets = writer.clearingSetsForClipper(
                true, false, false, false, false, true);
        assertEquals(2, sets.size());
        assertTrue(sets.containsKey("tracking_number"));
        assertTrue(sets.containsKey("freight_amount"));
    }

    // ── UPDATE-SQL shape ───────────────────────────────────────────

    @Test
    void buildUpdateSqlProducesParameterisedShape() {
        String sql = NdsShipmentOracleWriter.buildUpdateSql(
                "CLIPPER",
                new java.util.LinkedHashMap<>(Map.of("tracking_number", "1Z", "status", "SHIPPED")),
                "container_id IN (:ids)");
        assertTrue(sql.startsWith("UPDATE CLIPPER SET "), sql);
        assertTrue(sql.contains("tracking_number = :tracking_number"), sql);
        assertTrue(sql.contains("status = :status"), sql);
        assertTrue(sql.endsWith("WHERE container_id IN (:ids)"), sql);
    }

    // ── end-to-end write path ──────────────────────────────────────

    @Test
    void writeShipmentPushesFlaggedColumnsToClipperAndOeTracking() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        WritebackPayload p = WritebackPayload.builder()
                .clientCode("ACME")
                .trackingNumber("1Z999")
                .carrierCode("UPS")
                .packages(List.of(new WritebackPackagePayload(
                        1, "C001", List.of(10L, 11L), List.of(5001, 5002),
                        0, null, "LB", null)))
                .build();
        WritebackAck ack = writer.writeShipment(p);
        assertEquals(WritebackAck.Status.OK, ack.status());

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sqlCap.capture(), any(SqlParameterSource.class));
        List<String> sqls = sqlCap.getAllValues();
        assertTrue(sqls.stream().anyMatch(s -> s.contains("UPDATE CLIPPER")));
        assertTrue(sqls.stream().anyMatch(s -> s.contains("UPDATE OE_TRACKING")));
    }

    @Test
    void writeShipmentReturnsSkippedWhenClientCodeMissing() {
        WritebackAck ack = writer.writeShipment(WritebackPayload.builder()
                .trackingNumber("1Z999").build());
        assertEquals(WritebackAck.Status.SKIPPED, ack.status());
        verifyNoInteractions(jdbc);
    }

    @Test
    void writeShipmentReturnsSkippedWhenAllFieldsRedacted() {
        // Every payload field is null (dispatcher redacted them all).
        // packages non-null so lookup-key branches don't skip; but SET
        // clauses are empty so writer doesn't dial JdbcTemplate.
        WritebackPayload p = WritebackPayload.builder()
                .clientCode("ACME")
                .packages(List.of(new WritebackPackagePayload(
                        1, "C001", List.of(10L), List.of(5001),
                        0, null, "LB", null)))
                .build();
        WritebackAck ack = writer.writeShipment(p);
        assertEquals(WritebackAck.Status.SKIPPED, ack.status());
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void clearShipmentRespectsFlagMatrix() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        WritebackClearRequest req = new WritebackClearRequest(
                "nds-default", "1Z999", 1001, "ACME",
                List.of(10L), List.of(5001), Map.of(),
                true, false, true, false, false, false,
                null, null);

        WritebackAck ack = writer.clearShipment(req, true, false, true, false, false, false);
        assertEquals(WritebackAck.Status.OK, ack.status());

        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sqlCap.capture(), params.capture());
        // At least one UPDATE must set BOTH tracking + status but NOT
        // carrier / service / freight / ship_date.
        boolean foundBoth = false;
        for (int i = 0; i < sqlCap.getAllValues().size(); i++) {
            String sql = sqlCap.getAllValues().get(i);
            SqlParameterSource src = params.getAllValues().get(i);
            if (!(src instanceof MapSqlParameterSource m)) continue;
            if (sql.contains("UPDATE CLIPPER")) {
                assertTrue(sql.contains("tracking_number = :tracking_number"), sql);
                assertTrue(sql.contains("status = :status"), sql);
                assertFalse(sql.contains("carrier_code = "), sql);
                assertEquals("VOIDED", m.getValue("status"));
                foundBoth = true;
            }
        }
        assertTrue(foundBoth, "expected a CLIPPER update with tracking + status only");
    }
}
