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
        // B4 — SHIPPED_FLAG + HOLD_FLAG now selected so the block-gate at
        // NdsShipmentLookupService:332-337 actually fires. Same query
        // covers CUST_PO / DEPARTMENT / INCOTERMS / CURRENCY_CD which the
        // audit flagged as latent gaps — all four are needed downstream
        // by CustomsService/label rendering. SHIP_TO_EMAIL stays TBD (a
        // separate audit item, may live on a different NDS table).
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
                       SHIPVIA_CD,
                       SHIPPED_FLAG,
                       HOLD_FLAG,
                       CUST_PO,
                       DEPARTMENT,
                       INCOTERMS,
                       CURRENCY_CD
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
                            null,   // SHIP_TO_EMAIL — TBD (separate audit item)
                            rs.getString("SHIPVIA_CD"),
                            rs.getString("SHIPPED_FLAG"),
                            rs.getString("HOLD_FLAG"),
                            rs.getString("CUST_PO"),
                            rs.getString("DEPARTMENT"),
                            rs.getString("INCOTERMS"),
                            rs.getString("CURRENCY_CD"))));
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
        // B2 — was OE_INTL_ITEMS (invented, only existed in the test
        // fixture). ShipX reads TB_CLIPPER_ITEM_DETL on the CI item path
        // (08a-quickship.md:126-152). Column names below match the shape
        // ShipX/DAL* use; if a prod install has different column names,
        // TB_CLIPPER_ITEM_DETL's own DDL should confirm and a per-connection
        // query template (audit F10) will let ops override without a code
        // change.
        String sql = """
                SELECT DESCRIPTION,
                       QUANTITY,
                       UNIT_VALUE,
                       CURRENCY_CD,
                       HS_CODE,
                       COUNTRY_OF_ORIGIN,
                       UNIT_WEIGHT_LB
                  FROM TB_CLIPPER_ITEM_DETL
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
        return findBatchOwner(batchId, null);
    }

    /**
     * Real TB_BILLABLE_CONTAINERS shape (via /admin/nds-debug/describe):
     * {@code BATCH_ID CONTAINER_ID WEIGHT CONTAINER_NO ORDER_NO
     * ORDER_SUFFIX FF_SCHEMA}. No PRIMARY_CLIENT_CODE — {@code FF_SCHEMA}
     * IS the tenant/client code (same string {@link NdsTemplates#forClient}
     * accepts). When {@code clientCode} is non-blank the SQL narrows to
     * that tenant so a batch id colliding across tenants can't leak.
     */
    public Optional<BatchOwner> findBatchOwner(String batchId, String clientCode) {
        boolean hasClient = clientCode != null && !clientCode.isBlank();
        String sql = hasClient
                ? """
                        SELECT FF_SCHEMA
                          FROM TB_BILLABLE_CONTAINERS
                         WHERE BATCH_ID  = :batchId
                           AND FF_SCHEMA = :clientCode
                           AND ROWNUM    = 1
                        """
                : """
                        SELECT FF_SCHEMA
                          FROM TB_BILLABLE_CONTAINERS
                         WHERE BATCH_ID = :batchId
                           AND ROWNUM  = 1
                        """;
        MapSqlParameterSource p = new MapSqlParameterSource("batchId", batchId);
        if (hasClient) p.addValue("clientCode", clientCode.trim());
        try {
            return Optional.of(templates.production().queryForObject(sql, p, (rs, i) -> {
                String ff = rs.getString("FF_SCHEMA");
                return new BatchOwner(ff, ff);
            }));
        } catch (EmptyResultDataAccessException empty) {
            return Optional.empty();
        }
    }

    // ═════════════════ .Y (per-client) — CLIENT login ═════════════════

    /**
     * One physical box in a billable batch — one row per
     * {@code (batch_id, container_no, order_suffix)}. Container IDs +
     * order numbers within the box arrive as CSV strings (XMLAGG in
     * SQL); the service splits them into typed lists.
     *
     * <p>Weight is {@code TB_BILLABLE_CONTAINERS.WEIGHT} — legacy
     * ShipX ProcessBillableShipments explicitly reads this, not
     * {@code OE_SHIP_CONTAINER.GROSS_WT}, because billable weight is
     * the pre-computed rated weight for the box.
     */
    public record BillableBatchPackage(Integer containerNo,
                                       Integer orderSuffix,
                                       BigDecimal weight,
                                       String containerIdsCsv,
                                       String orderNosCsv) {}

    /**
     * Step 2 — one row per physical box in the batch. Runs on the
     * CLIENT login. Joins {@code oe_ship_container} (the client's
     * per-order shipping containers, tenant-scoped by login) to
     * {@code tb_billable_containers} (batch definition, reachable
     * via synonym) on {@code (Order_NO, Order_Suffix)}.
     *
     * <p>Legacy ShipX doc names {@code tb_ship_container} for the
     * join partner, but real data lives in {@code oe_ship_container}
     * on the client login (confirmed via /admin/nds-debug/billable-batch).
     * The tenant filter is redundant here — client login is already
     * tenant-scoped — so only {@code b.FF_SCHEMA} narrows the batch.
     *
     * <p>The two aggregate subqueries collect the container_ids /
     * order_nos that share a {@code container_no} within the batch
     * (XMLAGG works on Oracle 10g+ where LISTAGG isn't available).
     * {@code DISTINCT} collapses the row set to one per
     * (batch, container_no, order_suffix).
     */
    public List<BillableBatchPackage> findBillableBatchPackages(String batchId, String clientCode) {
        String sql = """
                SELECT DISTINCT
                       b.Container_No,
                       a.Order_Suffix,
                       b.WEIGHT,
                       (SELECT CAST(RTRIM(XMLAGG(XMLELEMENT(E, c.Container_ID || ',').EXTRACT('//text()')
                                          ORDER BY c.Container_ID).GetClobVal(), ',') AS VARCHAR2(4000))
                          FROM tb_billable_containers c
                         WHERE c.batch_id = b.batch_id AND c.Container_No = b.Container_No) AS Container_ID_CSV,
                       (SELECT CAST(RTRIM(XMLAGG(XMLELEMENT(E, o.Order_NO || ',').EXTRACT('//text()')
                                          ORDER BY o.Order_NO).GetClobVal(), ',') AS VARCHAR2(4000))
                          FROM tb_billable_containers o
                         WHERE o.batch_id = b.batch_id AND o.Container_No = b.Container_No) AS Order_NO_CSV
                  FROM oe_ship_container a
                  JOIN tb_billable_containers b ON a.Order_NO = b.Order_NO AND a.Order_Suffix = b.Order_Suffix
                 WHERE b.batch_id  = :batchId
                   AND b.FF_SCHEMA = :clientCode
                 ORDER BY b.Container_No, a.Order_Suffix
                """;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("batchId", batchId)
                .addValue("clientCode", clientCode);
        return templates.forClient(clientCode).query(sql, p, (rs, i) -> {
            // Oracle NUMBER → BigDecimal via JDBC; can't (Integer)-cast directly.
            java.math.BigDecimal cn = rs.getBigDecimal("Container_No");
            java.math.BigDecimal os = rs.getBigDecimal("Order_Suffix");
            return new BillableBatchPackage(
                    cn == null ? null : cn.intValue(),
                    os == null ? null : os.intValue(),
                    rs.getBigDecimal("WEIGHT"),
                    rs.getString("Container_ID_CSV"),
                    rs.getString("Order_NO_CSV"));
        });
    }

}
