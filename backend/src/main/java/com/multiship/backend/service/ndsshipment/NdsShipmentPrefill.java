package com.multiship.backend.service.ndsshipment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The response returned by {@link NdsShipmentLookupService#lookup(String)}
 * and served by {@code NdsShipmentLookupController} on
 * {@code GET /api/v1/manual-shipment/nds-lookup?scan=...}.
 *
 * <p>Shape locked in the design phase — the FE's PR2 wiring binds to
 * these field names, so renames here cascade. Fields carrying enough
 * metadata for the eventual writeback (container_ids[], order_nos[],
 * order_suffix per package) are persisted at INFO level in service
 * logs; addresses NEVER are.
 *
 * <p>Status semantics:
 * <ul>
 *   <li>{@code OK} — everything prefillable; FE lights the green strip.</li>
 *   <li>{@code WARNING} — mostly-prefillable but with soft issues in
 *       {@code messages[]} (e.g. unmapped ship method, weight missing,
 *       duplicate suffix on a batch container).</li>
 *   <li>{@code BLOCKED} — hard rules failed (HLD ship method, already-
 *       shipped container, address/shipvia mismatch across batch orders);
 *       the FE renders read-only refs + package list but disables Ship.</li>
 * </ul>
 */
public record NdsShipmentPrefill(
        Status status,
        List<Message> messages,
        Scope scope,
        String scannedValue,
        String clientCode,
        String batchId,               // null for DIRECT
        List<Order> orders,
        Recipient recipient,          // null on BLOCKED with no readable order
        ShipMethod shipMethod,        // null on BLOCKED "no ship method"
        List<Package> packages,
        Notify notifyBlock,
        International international,  // null when SHIPTO_COUNTRY_CD = US
        List<String> defaultedFields  // e.g. ["recipient.phone", "notify.sendTo"]
) {

    public enum Status { OK, WARNING, BLOCKED }
    public enum Scope { DIRECT, BATCH }

    /** Message severity mirrors {@link Status} for per-message granularity. */
    public record Message(Severity severity, String text) {
        public enum Severity { INFO, WARNING, BLOCKED }
    }

    public record Order(
            Integer orderNo,
            Integer orderSuffix,
            String invNo,
            String thpAccount
    ) {}

    public record Recipient(
            String attn,               // SHIP_ATTN (company)
            String name,               // SHIP_NAME
            String addr1,              // SHIP_ADDR1
            String addr2,              // SHIP_ADDR2
            String addr3,              // SHIP_ADDR3
            String city,
            String state,
            String zip,
            String countryCd,
            String phone,              // may be defaulted to +1 616 772 3513
            boolean phoneDefaulted,
            Integer sourceOrderNo      // which order this address came from (batch case)
    ) {}

    public record ShipMethod(
            String code,               // SHIPVIA_CD
            String description,        // SHIPVIA_DESC
            Long mappedServiceId       // null when code not mapped in Settings
    ) {}

    /**
     * One physical container = one package row (or two if the same container
     * belongs to two different order_suffix rows — that's the ShipX
     * "duplicate suffix" case; both surface with a WARNING message).
     */
    public record Package(
            int sequence,              // 1-based; matches FE row ordering
            String containerNo,
            List<Long> containerIds,   // usually 1; multiple in .Y multi-order case
            List<Integer> orderNos,    // usually 1; multiple in .Y multi-order case
            Integer orderSuffix,
            BigDecimal weight,         // null when NDS has no weight; FE flags "weight missing"
            String weightSource,       // "TB_SHIP_CONTAINER.WEIGHT" | "OE_SHIP_CONTAINER.GROSS_WT" | "TB_BILLABLE_CONTAINERS.WEIGHT" | null
            BigDecimal length,
            BigDecimal width,
            BigDecimal height,
            LocalDateTime packDt,
            String shippedFlag,        // raw NDS flag; blocked if any container has this set
            boolean isScanned          // true for the exact container the shipper scanned (.X only)
    ) {}

    public record Notify(
            String sendTo,             // OE_SEND_TO.SEND_TO
            String copyTo,             // OE_SEND_TO.COPY_TO
            boolean emailDefaulted     // true when both were empty and support@ was substituted
    ) {}

    public record International(
            boolean international,     // true when SHIPTO_COUNTRY_CD != 'US'
            int packageCount,          // cross-check count from TB_SHIP_CONTAINER
            BigDecimal totalWeight,    // cross-check sum from TB_SHIP_CONTAINER
            String letterOfCredit,     // OEHEAD.LETTER_OF_CREDIT
            List<Item> items
    ) {}

    /**
     * One row of the commercial invoice, tagged with its owning order so the
     * writeback step can attribute per-order.
     */
    public record Item(
            Integer orderNo,
            Integer lineNo,
            Integer linkLineNo,
            String itemNo,
            BigDecimal unitPrice,
            BigDecimal customsDeclValue,
            String description,
            Integer qtyShipped,
            String countryOfOrigin,
            String harmonizeCode,
            String harmonizeCodeDesc,
            BigDecimal unitCost
    ) {}
}
