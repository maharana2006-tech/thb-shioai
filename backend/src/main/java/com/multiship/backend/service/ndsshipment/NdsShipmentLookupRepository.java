package com.multiship.backend.service.ndsshipment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Oracle read layer for the NDS Shipment prefill feature.
 *
 * <p>Uses {@link NdsTemplates} for connection dispensing so callers
 * don't handle {@code javax.sql.DataSource} plumbing. All parameters
 * are bound (never concatenated into SQL text) — this is a
 * defence-in-depth boundary because the {@code clientCode} arrives
 * via a scanner and could originate from a poisoned label.
 *
 * <p>Two logical entry points:
 * <ul>
 *   <li>{@link #findContainerOwner(String)} — PRODUCTION login,
 *       given a container ID, returns the {@code (tenantId, orderNo,
 *       orderSuffix)} tuple. This is the {@code .X} handoff to the
 *       per-client queries.</li>
 *   <li>{@link #findBatchOwner(String)} — PRODUCTION login, given a
 *       batch ID, returns the {@code (ffSchema, primaryClientCode)}
 *       tuple. This is the {@code .Y} handoff.</li>
 * </ul>
 *
 * <p>Everything else runs against the CLIENT login (per-tenant
 * schema). {@link Optional} is used where a missing row is expected
 * business flow — the service treats it as a BLOCKED or WARNING
 * verdict rather than an error.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class NdsShipmentLookupRepository {

    private final NdsTemplates templates;

    // ═════════════════ .X (container) — PRODUCTION login ══════════════

    /** Row shape returned by {@link #findContainerOwner(String)}. */
    public record ContainerOwner(String clientCode,
                                 String orderNo,
                                 String orderSuffix) {}

    public Optional<ContainerOwner> findContainerOwner(String containerId) {
        String sql = """
                SELECT TENANT_ID     AS client_code,
                       ORDER_NO      AS order_no,
                       ORDER_SUFFIX  AS order_suffix
                  FROM TB_SHIP_CONTAINER
                 WHERE CONTAINER_ID = :containerId
                """;
        MapSqlParameterSource p = new MapSqlParameterSource("containerId", containerId);
        try {
            return Optional.of(templates.production().queryForObject(sql, p, (rs, i) ->
                    new ContainerOwner(rs.getString("client_code"),
                            rs.getString("order_no"),
                            rs.getString("order_suffix"))));
        } catch (EmptyResultDataAccessException empty) {
            return Optional.empty();
        }
    }

    // ═════════════════ .X (per-order) — CLIENT login ══════════════════

    /**
     * OEHEAD projection covering everything the prefill DTO needs from
     * the order header. Nullable fields left nullable — the service
     * layer decides whether a null triggers WARNING/BLOCKED.
     */
    public record OrderHeader(
            String orderNo,
            String orderSuffix,
            String shipToName,
            String shipToAttn,
            String shipToAddr1,
            String shipToAddr2,
            String shipToAddr3,
            String shipToCity,
            String shipToState,
            String shipToPostal,
            String shipToCountry,
            String shipToPhone,
            String shipToEmail,
            String shipviaCd,
            String shippedFlag,
            String holdFlag,
            String custPo,
            String department,
            String incoterms,
            String currency) {}

    public Optional<OrderHeader> findOrderHeader(String clientCode, String orderNo, String orderSuffix) {
        // Ship-to columns use the real OEHEAD names confirmed by ops
        // (SHIP_NAME / SHIP_ADDR1..3 / SHIPTO_*). Still not selected —
        // not present on real OEHEAD or not yet confirmed: SHIP_TO_EMAIL,
        // SHIPPED_FLAG, HOLD_FLAG, CUST_PO, DEPARTMENT, INCOTERMS, CURRENCY_CD.
        String sql = """
                SELECT ORDER_NO,
                       ORDER_SUFFIX,
                       SHIP_NAME,
                       SHIP_ATTN,
                       SHIP_ADDR1,
                       SHIP_ADDR2,
                       SHIP_ADDR3,
                       SHIPTO_CITY,
                       SHIPTO_STATE,
                       SHIPTO_ZIP,
                       SHIPTO_COUNTRY_CD,
                       SHIPTO_RECIP_PHONE,
                       SHIPVIA_CD
                  FROM OEHEAD
                 WHERE ORDER_NO     = :orderNo
                   AND ORDER_SUFFIX = :orderSuffix
                """;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("orderNo", orderNo)
                .addValue("orderSuffix", orderSuffix);
        try {
            return Optional.of(templates.forClient(clientCode).queryForObject(sql, p, (rs, i) ->
                    new OrderHeader(
                            rs.getString("ORDER_NO"),
                            rs.getString("ORDER_SUFFIX"),
                            rs.getString("SHIP_NAME"),
                            rs.getString("SHIP_ATTN"),
                            rs.getString("SHIP_ADDR1"),
                            rs.getString("SHIP_ADDR2"),
                            rs.getString("SHIP_ADDR3"),
                            rs.getString("SHIPTO_CITY"),
                            rs.getString("SHIPTO_STATE"),
                            rs.getString("SHIPTO_ZIP"),
                            rs.getString("SHIPTO_COUNTRY_CD"),
                            rs.getString("SHIPTO_RECIP_PHONE"),
                            null,   // SHIP_TO_EMAIL — TBD
                            rs.getString("SHIPVIA_CD"),
                            null,   // SHIPPED_FLAG — TBD
                            null,   // HOLD_FLAG — TBD
                            null,   // CUST_PO — TBD
                            null,   // DEPARTMENT — TBD
                            null,   // INCOTERMS — TBD
                            null))); // CURRENCY_CD — TBD
        } catch (EmptyResultDataAccessException empty) {
            return Optional.empty();
        }
    }

    /**
     * A single container inside an order. {@code billableWeight} is
     * the weight the label should print; the service layer packages
     * one FE box per row and never sums.
     */
    public record ContainerRow(String containerId,
                               String orderNo,
                               String orderSuffix,
                               BigDecimal billableWeightLb,
                               BigDecimal lengthIn,
                               BigDecimal widthIn,
                               BigDecimal heightIn,
                               String packageTypeCd) {}

    public List<ContainerRow> findContainers(String clientCode, String orderNo, String orderSuffix) {
        String sql = """
                SELECT CONTAINER_ID,
                       ORDER_NO,
                       ORDER_SUFFIX,
                       GROSS_WT,
                       LENGTH,
                       WIDTH,
                       HEIGHT,
                       CONTAINER_TYPE
                  FROM OE_SHIP_CONTAINER
                 WHERE ORDER_NO     = :orderNo
                   AND ORDER_SUFFIX = :orderSuffix
                 ORDER BY CONTAINER_ID
                """;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("orderNo", orderNo)
                .addValue("orderSuffix", orderSuffix);
        return templates.forClient(clientCode).query(sql, p, (rs, i) ->
                new ContainerRow(
                        rs.getString("CONTAINER_ID"),
                        rs.getString("ORDER_NO"),
                        rs.getString("ORDER_SUFFIX"),
                        rs.getBigDecimal("GROSS_WT"),
                        rs.getBigDecimal("LENGTH"),
                        rs.getBigDecimal("WIDTH"),
                        rs.getBigDecimal("HEIGHT"),
                        rs.getString("CONTAINER_TYPE")));
    }

    /** SHIPVIA description looked up per client. */
    public record ShipMethod(String shipviaCd, String description) {}

    public Optional<ShipMethod> findShipMethod(String clientCode, String shipviaCd) {
        String sql = """
                SELECT SHIPVIA_CD, SHIPVIA_DESC
                  FROM SHIPVIA
                 WHERE SHIPVIA_CD = :shipviaCd
                """;
        MapSqlParameterSource p = new MapSqlParameterSource("shipviaCd", shipviaCd);
        try {
            return Optional.of(templates.forClient(clientCode).queryForObject(sql, p, (rs, i) ->
                    new ShipMethod(rs.getString("SHIPVIA_CD"), rs.getString("SHIPVIA_DESC"))));
        } catch (EmptyResultDataAccessException empty) {
            return Optional.empty();
        }
    }

    /** Notify email attached to the order in OE_SEND_TO. */
    public record NotifyEmail(String email, String label) {}

    public List<NotifyEmail> findNotifyEmails(String clientCode, String orderNo, String orderSuffix) {
        String sql = """
                SELECT SEND_TO, SEND_TYPE
                  FROM OE_SEND_TO
                 WHERE ORDER_NO     = :orderNo
                   AND ORDER_SUFFIX = :orderSuffix
                   AND SEND_TO IS NOT NULL
                 ORDER BY SEND_TO
                """;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("orderNo", orderNo)
                .addValue("orderSuffix", orderSuffix);
        return templates.forClient(clientCode).query(sql, p, (rs, i) ->
                new NotifyEmail(rs.getString("SEND_TO"), rs.getString("SEND_TYPE")));
    }

    /** Commercial-invoice / customs line item for international shipments. */
    public record InternationalItem(String description,
                                    int quantity,
                                    BigDecimal unitValue,
                                    String currency,
                                    String hsCode,
                                    String countryOfOrigin,
                                    BigDecimal unitWeightLb) {}

    public List<InternationalItem> findInternationalItems(String clientCode,
                                                          String orderNo,
                                                          String orderSuffix) {
        String sql = """
                SELECT DESCRIPTION,
                       QUANTITY,
                       UNIT_VALUE,
                       CURRENCY_CD,
                       HS_CODE,
                       COUNTRY_OF_ORIGIN,
                       UNIT_WEIGHT_LB
                  FROM OE_INTL_ITEMS
                 WHERE ORDER_NO     = :orderNo
                   AND ORDER_SUFFIX = :orderSuffix
                 ORDER BY LINE_NO
                """;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("orderNo", orderNo)
                .addValue("orderSuffix", orderSuffix);
        return templates.forClient(clientCode).query(sql, p, (rs, i) ->
                new InternationalItem(
                        rs.getString("DESCRIPTION"),
                        rs.getInt("QUANTITY"),
                        rs.getBigDecimal("UNIT_VALUE"),
                        rs.getString("CURRENCY_CD"),
                        rs.getString("HS_CODE"),
                        rs.getString("COUNTRY_OF_ORIGIN"),
                        rs.getBigDecimal("UNIT_WEIGHT_LB")));
    }

    // ═════════════════ .Y (batch) — PRODUCTION login ══════════════════

    /** Row shape returned by {@link #findBatchOwner(String)}. */
    public record BatchOwner(String ffSchema,
                             String primaryClientCode) {}

    public Optional<BatchOwner> findBatchOwner(String batchId) {
        String sql = """
                SELECT FF_SCHEMA,
                       PRIMARY_CLIENT_CODE
                  FROM TB_BILLABLE_CONTAINERS
                 WHERE BATCH_ID = :batchId
                   AND ROWNUM  = 1
                """;
        MapSqlParameterSource p = new MapSqlParameterSource("batchId", batchId);
        try {
            return Optional.of(templates.production().queryForObject(sql, p, (rs, i) ->
                    new BatchOwner(rs.getString("FF_SCHEMA"),
                            rs.getString("PRIMARY_CLIENT_CODE"))));
        } catch (EmptyResultDataAccessException empty) {
            return Optional.empty();
        }
    }

    /**
     * Full batch content — every {@code (orderNo, orderSuffix,
     * containerId)} triple that composes the batch, ordered by
     * container so the service's "lowest-container order supplies
     * the ship-to" rule is trivial to apply.
     */
    public record BatchContainer(String containerId,
                                 String orderNo,
                                 String orderSuffix) {}

    public List<BatchContainer> findBatchContents(String batchId) {
        String sql = """
                SELECT CONTAINER_ID,
                       ORDER_NO,
                       ORDER_SUFFIX
                  FROM TB_BILLABLE_CONTAINERS
                 WHERE BATCH_ID = :batchId
                 ORDER BY CONTAINER_ID
                """;
        MapSqlParameterSource p = new MapSqlParameterSource("batchId", batchId);
        return templates.production().query(sql, p, (rs, i) ->
                new BatchContainer(
                        rs.getString("CONTAINER_ID"),
                        rs.getString("ORDER_NO"),
                        rs.getString("ORDER_SUFFIX")));
    }

    // ═════════════════ .Y (per-client) — CLIENT login ═════════════════

    /**
     * Aggregated package view for a batch — one row per container id
     * with the billable weight already joined from OE_SHIP_CONTAINER.
     * The service pastes each row 1:1 into the FE's package list.
     */
    public record BatchPackage(String containerId,
                               String orderNo,
                               String orderSuffix,
                               BigDecimal billableWeightLb,
                               BigDecimal lengthIn,
                               BigDecimal widthIn,
                               BigDecimal heightIn,
                               String packageTypeCd) {}

    public List<BatchPackage> findBatchPackagesGrouped(String clientCode,
                                                       List<String> containerIds) {
        if (containerIds == null || containerIds.isEmpty()) {
            return List.of();
        }
        String sql = """
                SELECT CONTAINER_ID,
                       ORDER_NO,
                       ORDER_SUFFIX,
                       GROSS_WT,
                       LENGTH,
                       WIDTH,
                       HEIGHT,
                       CONTAINER_TYPE
                  FROM OE_SHIP_CONTAINER
                 WHERE CONTAINER_ID IN (:containerIds)
                 ORDER BY CONTAINER_ID
                """;
        MapSqlParameterSource p = new MapSqlParameterSource("containerIds", containerIds);
        return templates.forClient(clientCode).query(sql, p, (rs, i) ->
                new BatchPackage(
                        rs.getString("CONTAINER_ID"),
                        rs.getString("ORDER_NO"),
                        rs.getString("ORDER_SUFFIX"),
                        rs.getBigDecimal("GROSS_WT"),
                        rs.getBigDecimal("LENGTH"),
                        rs.getBigDecimal("WIDTH"),
                        rs.getBigDecimal("HEIGHT"),
                        rs.getString("CONTAINER_TYPE")));
    }
}
