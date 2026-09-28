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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V89 — real Oracle writeback for NDS. Called by
 * {@link com.multiship.backend.service.externalsystems.connectors.NdsOracleConnector}
 * from {@code writeShipment} / {@code clearShipment}.
 *
 * <p>Three tables touched (per NDS convention documented on
 * {@link NdsShipmentWriteback}):
 * <ul>
 *   <li>{@code CLIPPER} (CLIENT schema) — per-container physical row.
 *       Composite key: {@code TENANT_ID + ORDER_NO + CONTAINER_NO + ORDER_SUFFIX}.
 *       Only touched on outbound SHIPMENT (mode="SHIPMENT"); returns skip it.
 *       Only tracking + freight columns are updated.</li>
 *   <li>{@code OE_TRACKING} (CLIENT schema) — order-level tracking history.
 *       Kept from the V89 shape until CLIENT-schema DDL can be verified.</li>
 *   <li>{@code TB_MANUAL_SHIPMENT} (PRODUCTION schema) — manual-shipment log.
 *       ONE INSERT per label generate: {@code ERROR_MODE='M'} for outbound,
 *       {@code 'R'} for returns (with {@code ORDER_NO='REN -'+orderNo}).
 *       An ADDITIONAL {@code 'Q'} row fires alongside when {@code note} is
 *       non-blank — audit trail for operator-entered notes.</li>
 * </ul>
 *
 * <p>Column names are canonical PRODUCTION.TB_MANUAL_SHIPMENT captured via
 * NdsDebugController /describe on 2026-09-28.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NdsShipmentOracleWriter {

    private final NdsTemplates templates;

    public WritebackAck writeShipment(WritebackPayload p) {
        if (p.clientCode() == null || p.clientCode().isBlank()) {
            return WritebackAck.skipped("nds: no clientCode on payload — cannot pick schema");
        }
        NamedParameterJdbcTemplate clientJdbc;
        try {
            clientJdbc = templates.forClient(p.clientCode());
        } catch (Exception e) {
            return WritebackAck.failed("nds: cannot open CLIENT pool for " + p.clientCode()
                    + ": " + e.getMessage());
        }
        boolean isReturn = "RETURN".equalsIgnoreCase(p.shipmentMode());
        boolean isDtc    = "DTC".equalsIgnoreCase(p.source());
        List<String> touched = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // G1 — DTC surface writes to a DIFFERENT set of tables than
        // Quick-Ship / BackOrder / Manual (per ShipX_NDS_Orders_and_Tracking.docx §6):
        //   • OE_TRACKING via <client>.PROC_OE.INSERT_OE_TRACKING (BATCHSHIP marker)
        //   • OE_SHIP_CONTAINER.CONTAINER_ID = tracking#
        // No CLIPPER, no TB_MANUAL_SHIPMENT.
        if (isDtc) {
            return dtcWriteShipment(clientJdbc, p);
        }

        // CLIPPER — CLIENT schema, composite-key UPDATE. Returns skip this
        // table (physical CLIPPER row is the outbound; return doesn't overwrite).
        if (!isReturn) {
            Map<String, Object> clipperSets = buildClipperSets(p, /* clearing= */ false);
            if (!clipperSets.isEmpty()) {
                int count = 0;
                for (WritebackPackagePayload pkg : p.packages()) {
                    if (pkg.orderNos() == null || pkg.orderNos().isEmpty()) continue;
                    // CONTAINER_NO is a NUMBER on NDS side; use the package sequence
                    // as a stable per-order counter (matches OE_SHIP_CONTAINER seed).
                    // ponytail: package.sequence used as CONTAINER_NO; if NDS
                    // keys off the raw container_no from prefill, promote that field
                    // onto WritebackPackagePayload.
                    for (Integer orderNo : pkg.orderNos()) {
                        try {
                            int rows = clientJdbc.update(
                                    "UPDATE CLIPPER SET TRACKING_NUMBER = :tracking, FREIGHT_AMOUNT = :freight "
                                            + " WHERE TENANT_ID = :tenant "
                                            + "   AND ORDER_NO = :orderNo "
                                            + "   AND CONTAINER_NO = :containerNo "
                                            + "   AND ORDER_SUFFIX = :orderSuffix",
                                    new MapSqlParameterSource()
                                            .addValue("tracking", p.trackingNumber())
                                            .addValue("freight", p.freightAmount())
                                            .addValue("tenant", p.clientCode())
                                            .addValue("orderNo", orderNo)
                                            .addValue("containerNo", pkg.sequence())
                                            .addValue("orderSuffix", pkg.orderSuffix() == null ? 0 : pkg.orderSuffix()));
                            count += rows;
                        } catch (Exception e) {
                            errors.add("CLIPPER: " + e.getMessage());
                        }
                    }
                }
                if (count > 0) touched.add("CLIPPER×" + count);
                // G2 — USPS parcels with no CLIPPER row on file need OE_TRACKING
                // via PROC_OE.INSERT_OE_TRACKING(source="CLIPPER", code="450") so
                // the tracking still makes it to NDS. Fires when the CLIPPER
                // UPDATE hit zero rows AND the carrier is a USPS variant.
                if (count == 0 && isUsps(p.carrierCode())) {
                    int procCount = 0;
                    for (WritebackPackagePayload pkg : p.packages()) {
                        if (pkg.orderNos() == null) continue;
                        for (Integer orderNo : pkg.orderNos()) {
                            try {
                                insertOeTrackingViaProc(clientJdbc, orderNo,
                                        pkg.orderSuffix() == null ? 0 : pkg.orderSuffix(),
                                        "CLIPPER", "450", p.trackingNumber());
                                procCount++;
                            } catch (Exception e) {
                                errors.add("OE_TRACKING(USPS proc): " + e.getMessage());
                            }
                        }
                    }
                    if (procCount > 0) touched.add("OE_TRACKING[PROC USPS]×" + procCount);
                }
            }
        }

        // OE_TRACKING — kept from V89 pending CLIENT-schema DDL verification.
        Map<String, Object> oetSets = buildOeTrackingSets(p, /* clearing= */ false);
        if (!oetSets.isEmpty()) {
            int count = 0;
            for (WritebackPackagePayload pkg : p.packages()) {
                if (pkg.orderNos() == null || pkg.orderNos().isEmpty()) continue;
                try {
                    int rows = clientJdbc.update(buildUpdateSql("OE_TRACKING", oetSets,
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

        // TB_MANUAL_SHIPMENT — PRODUCTION schema, INSERT one row per label.
        NamedParameterJdbcTemplate prodJdbc;
        try {
            prodJdbc = templates.production();
        } catch (Exception e) {
            errors.add("TB_MANUAL_SHIPMENT: cannot open PRODUCTION pool: " + e.getMessage());
            prodJdbc = null;
        }
        if (prodJdbc != null) {
            String errorMode = isReturn ? "R" : "M";
            String orderNoText = isReturn && p.orderNo() != null
                    ? ("REN -" + p.orderNo())
                    : (p.orderNo() != null ? String.valueOf(p.orderNo()) : null);
            // G10 — FedEx returns only write the R row when a note is entered;
            // UPS returns always. Per ShipX_NDS_Orders_and_Tracking.docx §7.
            boolean skipRRow = isReturn
                    && "FEDEX".equalsIgnoreCase(p.carrierCode())
                    && (p.note() == null || p.note().isBlank());
            if (!skipRRow) {
                try {
                    int rows = insertTbManualShipment(prodJdbc, p, errorMode, orderNoText, p.note());
                    if (rows > 0) touched.add("TB_MANUAL_SHIPMENT[" + errorMode + "]×" + rows);
                } catch (Exception e) {
                    errors.add("TB_MANUAL_SHIPMENT[" + errorMode + "]: " + e.getMessage());
                }
            }
            // ERROR_MODE=Q — additional audit row when the operator entered a note.
            if (p.note() != null && !p.note().isBlank()) {
                try {
                    int rows = insertTbManualShipment(prodJdbc, p, "Q", orderNoText, p.note());
                    if (rows > 0) touched.add("TB_MANUAL_SHIPMENT[Q]×" + rows);
                } catch (Exception e) {
                    errors.add("TB_MANUAL_SHIPMENT[Q]: " + e.getMessage());
                }
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

    /** Single-row INSERT into PRODUCTION.TB_MANUAL_SHIPMENT. Column order matches
     *  the schema captured 2026-09-28: TENANT_ID, CARRIER (NOT NULL), SHIP_SERVICE,
     *  THIRD_PARTY, SHIP_ATTN, COMPANY, ADDR1, ADDR2, CITY, STATE, COUNTRY, ZIP_CODE,
     *  SHIP_DATE, TRACKING, EMAIL, VOID_YN, ORDER_NO, FREIGHT, NOTE, ERROR_MODE. */
    private int insertTbManualShipment(NamedParameterJdbcTemplate jdbc, WritebackPayload p,
                                        String errorMode, String orderNoText, String note) {
        WritebackPayload.ShipTo to = p.shipTo() == null ? WritebackPayload.ShipTo.EMPTY : p.shipTo();
        String carrier = p.carrierDisplay() != null ? p.carrierDisplay()
                : (p.carrierCode() != null ? p.carrierCode() : "UNKNOWN"); // CARRIER is NOT NULL
        BigDecimal freight = p.freightAmount();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenant", p.clientCode())
                .addValue("carrier", carrier)
                .addValue("service", clamp(p.serviceDescription(), 50))
                .addValue("thirdParty", clamp(p.thirdPartyAccount(), 20))
                .addValue("attn", clamp(to.attn(), 50))
                .addValue("company", clamp(to.company(), 50))
                .addValue("addr1", clamp(to.addr1(), 50))
                .addValue("addr2", clamp(to.addr2(), 50))
                .addValue("city", clamp(to.city(), 50))
                .addValue("state", clamp(to.state(), 50))
                .addValue("country", clamp(to.country(), 50))
                .addValue("zip", clamp(to.postal(), 50))
                .addValue("shipDate", p.shipDate() == null ? null : Timestamp.valueOf(p.shipDate()))
                .addValue("tracking", clamp(p.trackingNumber(), 50))
                .addValue("email", clamp(to.email(), 50))
                .addValue("voidYn", "N")
                .addValue("orderNo", clamp(orderNoText, 50))
                .addValue("freight", freight == null ? null : freight.toPlainString())
                .addValue("note", clamp(note, 255))
                .addValue("errorMode", errorMode);
        return jdbc.update(
                "INSERT INTO TB_MANUAL_SHIPMENT (" +
                        "TENANT_ID, CARRIER, SHIP_SERVICE, THIRD_PARTY, " +
                        "SHIP_ATTN, COMPANY, ADDR1, ADDR2, CITY, STATE, COUNTRY, ZIP_CODE, " +
                        "SHIP_DATE, TRACKING, EMAIL, VOID_YN, ORDER_NO, FREIGHT, NOTE, ERROR_MODE) " +
                        "VALUES (" +
                        ":tenant, :carrier, :service, :thirdParty, " +
                        ":attn, :company, :addr1, :addr2, :city, :state, :country, :zip, " +
                        ":shipDate, :tracking, :email, :voidYn, :orderNo, :freight, :note, :errorMode)",
                params);
    }

    /**
     * Void-side: NULL out the flagged fields on CLIPPER + OE_TRACKING; on
     * TB_MANUAL_SHIPMENT the existing INSERT row gets VOID_YN='Y' by the
     * scan_value/tracking_number lookup. (INSERT-per-generate model means
     * the previous SHIPPED row stays as history; VOID_YN='Y' marks it voided.)
     */
    public WritebackAck clearShipment(WritebackClearRequest req, boolean hasTracking,
                                       boolean hasShipDate, boolean hasStatus,
                                       boolean hasCarrier, boolean hasService,
                                       boolean hasFreight) {
        if (req.clientCode() == null || req.clientCode().isBlank()) {
            return WritebackAck.skipped("nds: no clientCode on clear request");
        }
        NamedParameterJdbcTemplate clientJdbc;
        try {
            clientJdbc = templates.forClient(req.clientCode());
        } catch (Exception e) {
            return WritebackAck.failed("nds: cannot open CLIENT pool for " + req.clientCode()
                    + ": " + e.getMessage());
        }

        // G4 — DTC void reverses the DTC generate writes:
        //   • DELETE FROM OE_TRACKING WHERE TRACK_CD='BATCHSHIP' + order keys
        //   • UPDATE OE_SHIP_CONTAINER SET CONTAINER_ID = NULL
        // CLIPPER + TB_MANUAL_SHIPMENT are untouched on DTC (never written).
        if ("DTC".equalsIgnoreCase(req.source())) {
            return dtcClearShipment(clientJdbc, req);
        }

        List<String> touched = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // CLIPPER — composite key clear (tracking + freight only, mirrors generate).
        if ((hasTracking || hasFreight)
                && req.orderNos() != null && !req.orderNos().isEmpty()) {
            Map<String, Object> sets = new LinkedHashMap<>();
            if (hasTracking) sets.put("TRACKING_NUMBER", null);
            if (hasFreight) sets.put("FREIGHT_AMOUNT", null);
            try {
                int rows = clientJdbc.update(buildUpdateSql("CLIPPER", sets,
                        "TENANT_ID = :tenant AND ORDER_NO IN (:orderNos)"),
                        new MapSqlParameterSource()
                                .addValue("tenant", req.clientCode())
                                .addValue("orderNos", req.orderNos()));
                if (rows > 0) touched.add("CLIPPER×" + rows);
            } catch (Exception e) {
                errors.add("CLIPPER: " + e.getMessage());
            }
        }

        // OE_TRACKING — kept from V89 shape.
        Map<String, Object> oetSets = clearingSetsForOeTracking(hasTracking, hasShipDate,
                hasStatus, hasCarrier, hasService, hasFreight);
        if (!oetSets.isEmpty()) {
            List<Integer> orderNos = req.orderNos() != null && !req.orderNos().isEmpty()
                    ? req.orderNos()
                    : (req.orderNo() != null ? List.of(req.orderNo()) : List.of());
            if (!orderNos.isEmpty()) {
                try {
                    int rows = clientJdbc.update(buildUpdateSql("OE_TRACKING", oetSets,
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

        // TB_MANUAL_SHIPMENT — stamp VOID_YN='Y' on the row matching this tracking.
        if (hasTracking && req.trackingNumber() != null && !req.trackingNumber().isBlank()) {
            try {
                NamedParameterJdbcTemplate prodJdbc = templates.production();
                int rows = prodJdbc.update(
                        "UPDATE TB_MANUAL_SHIPMENT SET VOID_YN = :voidYn WHERE TRACKING = :tracking",
                        new MapSqlParameterSource()
                                .addValue("voidYn", "Y")
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

    /** CLIPPER columns updated on generate. Composite-key WHERE built inline. */
    Map<String, Object> buildClipperSets(WritebackPayload p, boolean clearing) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (p.trackingNumber() != null || clearing) s.put("TRACKING_NUMBER", p.trackingNumber());
        if (p.freightAmount() != null || clearing) s.put("FREIGHT_AMOUNT", p.freightAmount());
        return s;
    }

    /** OE_TRACKING columns updated on generate (unchanged from V89; pending DDL confirm). */
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

    // ── G4 — DTC-specific clear (void) path ─────────────────────────

    private WritebackAck dtcClearShipment(NamedParameterJdbcTemplate jdbc, WritebackClearRequest req) {
        java.util.List<Integer> orderNos = req.orderNos() != null && !req.orderNos().isEmpty()
                ? req.orderNos()
                : (req.orderNo() != null ? java.util.List.of(req.orderNo()) : java.util.List.of());
        if (orderNos.isEmpty()) {
            return WritebackAck.skipped("nds(DTC): no orderNos to clear");
        }
        List<String> touched = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int deleted = 0, nulled = 0;
        for (Integer orderNo : orderNos) {
            try {
                int rows = jdbc.update(
                        "DELETE FROM OE_TRACKING "
                                + " WHERE TRACK_CD = :trackCd "
                                + "   AND TENANT_ID = :tenant "
                                + "   AND ORDER_NO = :orderNo",
                        new MapSqlParameterSource()
                                .addValue("trackCd", "BATCHSHIP")
                                .addValue("tenant", req.clientCode())
                                .addValue("orderNo", orderNo));
                deleted += rows;
            } catch (Exception e) {
                errors.add("OE_TRACKING(BATCHSHIP delete): " + e.getMessage());
            }
            try {
                int rows = jdbc.update(
                        "UPDATE OE_SHIP_CONTAINER SET CONTAINER_ID = NULL "
                                + " WHERE TENANT_ID = :tenant "
                                + "   AND ORDER_NO = :orderNo",
                        new MapSqlParameterSource()
                                .addValue("tenant", req.clientCode())
                                .addValue("orderNo", orderNo));
                nulled += rows;
            } catch (Exception e) {
                errors.add("OE_SHIP_CONTAINER: " + e.getMessage());
            }
        }
        if (deleted > 0) touched.add("OE_TRACKING[BATCHSHIP delete]×" + deleted);
        if (nulled > 0) touched.add("OE_SHIP_CONTAINER[null]×" + nulled);
        if (!errors.isEmpty() && touched.isEmpty()) {
            return WritebackAck.failed("nds(DTC): all clear updates failed — " + String.join(" | ", errors));
        }
        if (touched.isEmpty()) {
            return WritebackAck.skipped("nds(DTC): no rows matched any lookup keys");
        }
        String detail = "nds(DTC): cleared " + String.join(", ", touched);
        if (!errors.isEmpty()) detail += " (errors: " + String.join(" | ", errors) + ")";
        return WritebackAck.ok(detail);
    }

    // ── G1 — DTC-specific write path ────────────────────────────────

    /**
     * DTC surface writeback per doc §6:
     *   • For every package's orderNo: call PROC_OE.INSERT_OE_TRACKING
     *     with source="BATCHSHIP", code="450".
     *   • For every package's orderNo: UPDATE OE_SHIP_CONTAINER SET
     *     CONTAINER_ID = tracking# WHERE TENANT_ID + ORDER_NO + ORDER_SUFFIX.
     * No CLIPPER, no TB_MANUAL_SHIPMENT.
     */
    private WritebackAck dtcWriteShipment(NamedParameterJdbcTemplate jdbc, WritebackPayload p) {
        List<String> touched = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int trackingRows = 0, containerRows = 0;
        for (WritebackPackagePayload pkg : p.packages()) {
            if (pkg.orderNos() == null || pkg.orderNos().isEmpty()) continue;
            Integer suffix = pkg.orderSuffix() == null ? 0 : pkg.orderSuffix();
            for (Integer orderNo : pkg.orderNos()) {
                try {
                    insertOeTrackingViaProc(jdbc, orderNo, suffix,
                            "BATCHSHIP", "450", p.trackingNumber());
                    trackingRows++;
                } catch (Exception e) {
                    errors.add("OE_TRACKING(BATCHSHIP): " + e.getMessage());
                }
                try {
                    int rows = jdbc.update(
                            "UPDATE OE_SHIP_CONTAINER SET CONTAINER_ID = :tracking "
                                    + " WHERE TENANT_ID = :tenant "
                                    + "   AND ORDER_NO = :orderNo "
                                    + "   AND ORDER_SUFFIX = :suffix",
                            new MapSqlParameterSource()
                                    .addValue("tracking", p.trackingNumber())
                                    .addValue("tenant", p.clientCode())
                                    .addValue("orderNo", orderNo)
                                    .addValue("suffix", suffix));
                    containerRows += rows;
                } catch (Exception e) {
                    errors.add("OE_SHIP_CONTAINER: " + e.getMessage());
                }
            }
        }
        if (trackingRows > 0) touched.add("OE_TRACKING[BATCHSHIP]×" + trackingRows);
        if (containerRows > 0) touched.add("OE_SHIP_CONTAINER×" + containerRows);
        if (!errors.isEmpty() && touched.isEmpty()) {
            return WritebackAck.failed("nds(DTC): all updates failed — " + String.join(" | ", errors));
        }
        if (touched.isEmpty()) {
            return WritebackAck.skipped("nds(DTC): no rows matched any lookup keys");
        }
        String detail = "nds(DTC): " + String.join(", ", touched);
        if (!errors.isEmpty()) detail += " (errors: " + String.join(" | ", errors) + ")";
        return WritebackAck.ok(detail);
    }

    /**
     * G1/G2 — call <client>.PROC_OE.INSERT_OE_TRACKING. Signature per doc:
     * (SYSDATE, ORDER_NO, ORDER_SUFFIX, source, code, tracking#). SYSDATE
     * is server-side default; we pass only the 5 caller-supplied params.
     *
     * ponytail: parameter names inferred (P_ORDER_NO etc.) from Oracle
     * convention because we can't reach the live procedure metadata from
     * dev (CLIENT login ORA-01017). If the first prod call fails with
     * "invalid identifier / wrong parameter", ops updates the names here.
     */
    private void insertOeTrackingViaProc(NamedParameterJdbcTemplate jdbc,
                                          Integer orderNo, Integer orderSuffix,
                                          String source, String code, String tracking) {
        org.springframework.jdbc.core.simple.SimpleJdbcCall call =
                new org.springframework.jdbc.core.simple.SimpleJdbcCall(jdbc.getJdbcTemplate())
                        .withCatalogName("PROC_OE")
                        .withProcedureName("INSERT_OE_TRACKING")
                        .withoutProcedureColumnMetaDataAccess()
                        .declareParameters(
                                new org.springframework.jdbc.core.SqlParameter("P_ORDER_NO", java.sql.Types.NUMERIC),
                                new org.springframework.jdbc.core.SqlParameter("P_ORDER_SUFFIX", java.sql.Types.NUMERIC),
                                new org.springframework.jdbc.core.SqlParameter("P_SOURCE", java.sql.Types.VARCHAR),
                                new org.springframework.jdbc.core.SqlParameter("P_CODE", java.sql.Types.VARCHAR),
                                new org.springframework.jdbc.core.SqlParameter("P_TRACKING", java.sql.Types.VARCHAR));
        call.execute(new MapSqlParameterSource()
                .addValue("P_ORDER_NO", orderNo)
                .addValue("P_ORDER_SUFFIX", orderSuffix)
                .addValue("P_SOURCE", source)
                .addValue("P_CODE", code)
                .addValue("P_TRACKING", tracking));
    }

    /** Any USPS-family carrier. Used by G2 fallback in the generate path. */
    private static boolean isUsps(String carrierCode) {
        if (carrierCode == null) return false;
        return switch (carrierCode.trim().toUpperCase()) {
            case "USPS", "USPS_DIRECT", "STAMPS_COM" -> true;
            default -> false;
        };
    }

    /** Truncate v to len chars so we never blow Oracle's VARCHAR2(N) limits. */
    private static String clamp(String v, int len) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        return t.length() <= len ? t : t.substring(0, len);
    }
}
