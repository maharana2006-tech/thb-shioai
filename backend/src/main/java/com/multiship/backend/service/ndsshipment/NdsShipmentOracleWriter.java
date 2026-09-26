package com.multiship.backend.service.ndsshipment;

import com.multiship.backend.service.externalsystems.writeback.WritebackAck;
import com.multiship.backend.service.externalsystems.writeback.WritebackClearRequest;
import com.multiship.backend.service.externalsystems.writeback.WritebackPackagePayload;
import com.multiship.backend.service.externalsystems.writeback.WritebackPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V89 — real Oracle writeback for NDS. Called by
 * {@link com.multiship.backend.service.externalsystems.connectors.NdsOracleConnector}
 * from {@code writeShipment} / {@code clearShipment}. Uses
 * {@link NdsTemplates#forClient(String)} to hit the client's schema
 * via the CLIENT-login Hikari pool the framework already manages.
 *
 * <p>Three tables touched (per NDS convention documented on
 * {@link NdsShipmentWriteback}):
 * <ul>
 *   <li>{@code CLIPPER} — per-container physical row. Keyed by
 *       container_id from {@link WritebackPackagePayload#containerIds()}.</li>
 *   <li>{@code OE_TRACKING} — order-level tracking history. Keyed by
 *       order_no from {@link WritebackPackagePayload#orderNos()}.</li>
 *   <li>{@code TB_MANUAL_SHIPMENT} — manual-scan shipment log. Keyed by
 *       {@code scannedValue} when the order originated from a {@code .X}
 *       or {@code .Y} scan.</li>
 * </ul>
 *
 * <p><b>Column-per-flag mapping.</b> Each of the six writeback flags maps
 * to one or more columns per table. Unflagged fields arrive as NULL from
 * the dispatcher; the writer skips the corresponding SET clause so the
 * existing column value is preserved.
 *
 * <p><b>Best-effort.</b> Every UPDATE runs in its own try/catch; a
 * failure on CLIPPER doesn't stop OE_TRACKING from being tried. The
 * ack aggregates the per-table outcomes into one detail string.
 *
 * <p><b>Ponytail note</b> — column names below are the canonical NDS
 * shape from {@code docs/nds-schema-reference.md}. If a live NDS schema
 * uses different names, override via a config field on
 * {@link com.multiship.backend.service.externalsystems.connectors.NdsOracleConfig}
 * — but don't preemptively split into a table-mapping struct until
 * that need materialises. ponytail: hardcoded NDS column names, promote
 * to config if a customer schema drifts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NdsShipmentOracleWriter {

    private final NdsTemplates templates;

    /**
     * Push flagged fields to the NDS Oracle schema for this client.
     * The dispatcher has already redacted unflagged fields to null.
     */
    public WritebackAck writeShipment(WritebackPayload p) {
        if (p.clientCode() == null || p.clientCode().isBlank()) {
            return WritebackAck.skipped("nds: no clientCode on payload — cannot pick schema");
        }
        NamedParameterJdbcTemplate jdbc;
        try {
            jdbc = templates.forClient(p.clientCode());
        } catch (Exception e) {
            return WritebackAck.failed("nds: cannot open CLIENT pool for " + p.clientCode()
                    + ": " + e.getMessage());
        }
        List<String> touched = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // CLIPPER — one UPDATE per container_id set on the packages.
        Map<String, Object> clipperSets = buildClipperSets(p, /* clearing= */ false);
        if (!clipperSets.isEmpty()) {
            int count = 0;
            for (WritebackPackagePayload pkg : p.packages()) {
                if (pkg.containerIds() == null || pkg.containerIds().isEmpty()) continue;
                try {
                    int rows = jdbc.update(buildUpdateSql("CLIPPER", clipperSets,
                            "container_id IN (:ids)"),
                            new MapSqlParameterSource()
                                    .addValues(clipperSets)
                                    .addValue("ids", pkg.containerIds()));
                    count += rows;
                } catch (Exception e) {
                    errors.add("CLIPPER: " + e.getMessage());
                }
            }
            if (count > 0) touched.add("CLIPPER×" + count);
        }

        // OE_TRACKING — one row per (order_no, order_suffix?) per package.
        Map<String, Object> oetSets = buildOeTrackingSets(p, /* clearing= */ false);
        if (!oetSets.isEmpty()) {
            int count = 0;
            for (WritebackPackagePayload pkg : p.packages()) {
                if (pkg.orderNos() == null || pkg.orderNos().isEmpty()) continue;
                try {
                    int rows = jdbc.update(buildUpdateSql("OE_TRACKING", oetSets,
                            "order_no IN (:orderNos)"),
                            new MapSqlParameterSource()
                                    .addValues(oetSets)
                                    .addValue("orderNos", pkg.orderNos()));
                    count += rows;
                } catch (Exception e) {
                    errors.add("OE_TRACKING: " + e.getMessage());
                }
            }
            if (count > 0) touched.add("OE_TRACKING×" + count);
        }

        // TB_MANUAL_SHIPMENT — one row per scannedValue.
        Map<String, Object> tbmsSets = buildTbManualShipmentSets(p, /* clearing= */ false);
        if (!tbmsSets.isEmpty() && p.scannedValue() != null && !p.scannedValue().isBlank()) {
            try {
                int rows = jdbc.update(buildUpdateSql("TB_MANUAL_SHIPMENT", tbmsSets,
                        "scan_value = :scan"),
                        new MapSqlParameterSource()
                                .addValues(tbmsSets)
                                .addValue("scan", p.scannedValue()));
                if (rows > 0) touched.add("TB_MANUAL_SHIPMENT×" + rows);
            } catch (Exception e) {
                errors.add("TB_MANUAL_SHIPMENT: " + e.getMessage());
            }
        }

        if (!errors.isEmpty() && touched.isEmpty()) {
            return WritebackAck.failed("nds: all updates failed — " + String.join(" | ", errors));
        }
        if (touched.isEmpty()) {
            return WritebackAck.skipped("nds: no rows matched any lookup keys");
        }
        String detail = "nds: updated " + String.join(", ", touched);
        if (!errors.isEmpty()) detail += " (errors: " + String.join(" | ", errors) + ")";
        return WritebackAck.ok(detail);
    }

    /**
     * Void-side: NULL out the same flagged fields the writeShipment set,
     * and stamp status = 'VOIDED' when the status flag is on. Same tables,
     * driven by the connection's flag matrix (already resolved at the
     * dispatcher — connectors here just see the payload).
     */
    public WritebackAck clearShipment(WritebackClearRequest req, boolean hasTracking,
                                       boolean hasShipDate, boolean hasStatus,
                                       boolean hasCarrier, boolean hasService,
                                       boolean hasFreight) {
        if (req.clientCode() == null || req.clientCode().isBlank()) {
            return WritebackAck.skipped("nds: no clientCode on clear request");
        }
        NamedParameterJdbcTemplate jdbc;
        try {
            jdbc = templates.forClient(req.clientCode());
        } catch (Exception e) {
            return WritebackAck.failed("nds: cannot open CLIENT pool for " + req.clientCode()
                    + ": " + e.getMessage());
        }
        Map<String, Object> clipperSets = clearingSetsForClipper(hasTracking, hasShipDate,
                hasStatus, hasCarrier, hasService, hasFreight);
        Map<String, Object> oetSets = clearingSetsForOeTracking(hasTracking, hasShipDate,
                hasStatus, hasCarrier, hasService, hasFreight);
        Map<String, Object> tbmsSets = clearingSetsForTbManualShipment(hasTracking, hasShipDate,
                hasStatus, hasCarrier, hasService, hasFreight);

        List<String> touched = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (!clipperSets.isEmpty()
                && req.containerIds() != null && !req.containerIds().isEmpty()) {
            try {
                int rows = jdbc.update(buildUpdateSql("CLIPPER", clipperSets,
                        "container_id IN (:ids)"),
                        new MapSqlParameterSource()
                                .addValues(clipperSets)
                                .addValue("ids", req.containerIds()));
                if (rows > 0) touched.add("CLIPPER×" + rows);
            } catch (Exception e) {
                errors.add("CLIPPER: " + e.getMessage());
            }
        }

        if (!oetSets.isEmpty()) {
            List<Integer> orderNos = req.orderNos() != null && !req.orderNos().isEmpty()
                    ? req.orderNos()
                    : (req.orderNo() != null ? List.of(req.orderNo()) : List.of());
            if (!orderNos.isEmpty()) {
                try {
                    int rows = jdbc.update(buildUpdateSql("OE_TRACKING", oetSets,
                            "order_no IN (:orderNos)"),
                            new MapSqlParameterSource()
                                    .addValues(oetSets)
                                    .addValue("orderNos", orderNos));
                    if (rows > 0) touched.add("OE_TRACKING×" + rows);
                } catch (Exception e) {
                    errors.add("OE_TRACKING: " + e.getMessage());
                }
            }
        }

        if (!tbmsSets.isEmpty() && req.trackingNumber() != null && !req.trackingNumber().isBlank()) {
            try {
                int rows = jdbc.update(buildUpdateSql("TB_MANUAL_SHIPMENT", tbmsSets,
                        "tracking_number = :tracking"),
                        new MapSqlParameterSource()
                                .addValues(tbmsSets)
                                .addValue("tracking", req.trackingNumber()));
                if (rows > 0) touched.add("TB_MANUAL_SHIPMENT×" + rows);
            } catch (Exception e) {
                errors.add("TB_MANUAL_SHIPMENT: " + e.getMessage());
            }
        }

        if (!errors.isEmpty() && touched.isEmpty()) {
            return WritebackAck.failed("nds: all clear updates failed — " + String.join(" | ", errors));
        }
        if (touched.isEmpty()) {
            return WritebackAck.skipped("nds: no rows matched any lookup keys");
        }
        String detail = "nds: cleared " + String.join(", ", touched);
        if (!errors.isEmpty()) detail += " (errors: " + String.join(" | ", errors) + ")";
        return WritebackAck.ok(detail);
    }

    // ── SET-clause builders (generate) ─────────────────────────────

    /** CLIPPER columns updated on generate. */
    Map<String, Object> buildClipperSets(WritebackPayload p, boolean clearing) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (p.trackingNumber() != null || clearing) s.put("tracking_number", p.trackingNumber());
        if (p.shipDate() != null || clearing) s.put("ship_date", p.shipDate());
        if (p.status() != null || clearing) s.put("status", p.status());
        if (p.carrierCode() != null || clearing) s.put("carrier_code", p.carrierCode());
        if (p.serviceCode() != null || clearing) s.put("service_code", p.serviceCode());
        if (p.freightAmount() != null || clearing) s.put("freight_amount", p.freightAmount());
        return s;
    }

    /** OE_TRACKING columns updated on generate. */
    Map<String, Object> buildOeTrackingSets(WritebackPayload p, boolean clearing) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (p.trackingNumber() != null || clearing) s.put("tracking_no", p.trackingNumber());
        if (p.shipDate() != null || clearing) s.put("ship_date", p.shipDate());
        if (p.status() != null || clearing) s.put("ship_status", p.status());
        if (p.carrierCode() != null || clearing) s.put("carrier_cd", p.carrierCode());
        if (p.serviceCode() != null || clearing) s.put("service_cd", p.serviceCode());
        if (p.freightAmount() != null || clearing) s.put("freight_amt", p.freightAmount());
        return s;
    }

    /** TB_MANUAL_SHIPMENT columns updated on generate. */
    Map<String, Object> buildTbManualShipmentSets(WritebackPayload p, boolean clearing) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (p.trackingNumber() != null || clearing) s.put("tracking_number", p.trackingNumber());
        if (p.shipDate() != null || clearing) s.put("ship_date", p.shipDate());
        if (p.status() != null || clearing) s.put("shipment_status", p.status());
        if (p.carrierCode() != null || clearing) s.put("carrier", p.carrierCode());
        if (p.serviceCode() != null || clearing) s.put("service_code", p.serviceCode());
        if (p.freightAmount() != null || clearing) s.put("freight", p.freightAmount());
        return s;
    }

    // ── SET-clause builders (clear) ────────────────────────────────

    Map<String, Object> clearingSetsForClipper(boolean t, boolean d, boolean s,
                                                boolean c, boolean sv, boolean f) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (t) m.put("tracking_number", null);
        if (d) m.put("ship_date", null);
        if (s) m.put("status", "VOIDED");
        if (c) m.put("carrier_code", null);
        if (sv) m.put("service_code", null);
        if (f) m.put("freight_amount", null);
        return m;
    }

    Map<String, Object> clearingSetsForOeTracking(boolean t, boolean d, boolean s,
                                                   boolean c, boolean sv, boolean f) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (t) m.put("tracking_no", null);
        if (d) m.put("ship_date", null);
        if (s) m.put("ship_status", "VOIDED");
        if (c) m.put("carrier_cd", null);
        if (sv) m.put("service_cd", null);
        if (f) m.put("freight_amt", null);
        return m;
    }

    Map<String, Object> clearingSetsForTbManualShipment(boolean t, boolean d, boolean s,
                                                        boolean c, boolean sv, boolean f) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (t) m.put("tracking_number", null);
        if (d) m.put("ship_date", null);
        if (s) m.put("shipment_status", "VOIDED");
        if (c) m.put("carrier", null);
        if (sv) m.put("service_code", null);
        if (f) m.put("freight", null);
        return m;
    }

    /**
     * Build a parameterised UPDATE. All keys in {@code sets} become
     * named params on the SET side; {@code whereClause} carries its own
     * named params (caller adds them to the SqlParameterSource).
     */
    static String buildUpdateSql(String table, Map<String, Object> sets, String whereClause) {
        StringBuilder sb = new StringBuilder("UPDATE ").append(table).append(" SET ");
        boolean first = true;
        for (String col : sets.keySet()) {
            if (!first) sb.append(", ");
            sb.append(col).append(" = :").append(col);
            first = false;
        }
        sb.append(" WHERE ").append(whereClause);
        return sb.toString();
    }
}
