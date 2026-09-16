package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "order_label_tracking")
@Data
public class OrderTracking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false)
    private Integer orderNo;

    @Column(name = "order_suffix")
    private Integer orderSuffix = 0;

    @Column(name = "tracking_number")
    private String trackingNumber;

    /** Sprint 52 — TEXT (was default varchar 255). Carrier tracking deep-
     *  links carry tokens + query params that regularly push over 255. */
    @Column(name = "tracking_url", columnDefinition = "text")
    private String trackingUrl;

    @Column(name = "ship_via_cd")
    private String shipViaCd;

    @Column(name = "is_label_generated")
    private Boolean isLabelGenerated = false;

    @Column(name = "label_generated_at")
    private LocalDateTime labelGeneratedAt;

    /** Sprint 52 — TEXT (was default varchar 255). Holds either a signed
     *  carrier URL (up to 2 KB on FedEx) OR the base64-encoded label
     *  bytes (10–200 KB). Sprint 52 PR B (#518) established this column
     *  carries both content types depending on what the carrier returned. */
    @Column(name = "label_file_path", columnDefinition = "text")
    private String labelFilePath;

    @Column(name = "status")
    private String status = "PENDING";

    /** Carrier account the label was generated with (feeds account-book usage stats). */
    @Column(name = "account_number", length = 100)
    private String accountNumber;

    /**
     * Client-supplied Idempotency-Key of the request that generated this
     * label. A retry carrying the same key gets the existing label back as a
     * success instead of a 409 conflict.
     */
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    /** Carrier error responses run 500-2000 chars (FedEx nested JSON) — text,
     *  not the JPA varchar(255) default. V31 intended this but its fresh-DB
     *  guard skipped databases where Hibernate created the table after
     *  Flyway ran; V36 re-widens and this mapping keeps new DBs correct. */
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    // ===== 3PL snapshot (populated at label time; stable if the client's
    // config later changes). Rate-strategy = FIXED and markups are enforced
    // by ShipmentResolutionService; these columns record what was actually
    // applied so historical bills don't shift.

    /** Warehouse the shipment shipped from — null for ad-hoc / no-client shipments. */
    @Column(name = "warehouse_code", length = 50)
    private String warehouseCode;

    /** Amount the carrier billed us, before markup. Same currency as {@link #markupCurrency}. */
    @Column(name = "carrier_amount", precision = 12, scale = 4)
    private BigDecimal carrierAmount;

    /** {@link #carrierAmount} + markup, rounded per shipment. This is what the client rebills at. */
    @Column(name = "billable_amount", precision = 12, scale = 4)
    private BigDecimal billableAmount;

    /** PERCENT | FLAT — the kind of markup applied to this shipment. */
    @Column(name = "markup_kind", length = 10)
    private String markupKind;

    /** The value snapshot — the % or flat amount that was applied. */
    @Column(name = "markup_value", precision = 12, scale = 4)
    private BigDecimal markupValue;

    /** ISO-4217 of the amounts above. */
    @Column(name = "markup_currency", length = 3)
    private String markupCurrency;

    /**
     * True when the label was created past the client's local cutoff — soft
     * flag for the WMS ("label valid; picked up next business day").
     */
    @Column(name = "dispatch_next_business_day")
    private Boolean dispatchNextBusinessDay;

    /**
     * Superseded labels, newest last — JSON list of
     * {event: VOIDED | REISSUED, trackingNumber, replacedBy?, at}. The live
     * tracking number lives in {@link #trackingNumber}; this is the audit
     * trail a customs or refund query needs after a reissue.
     */
    @Column(name = "label_history", columnDefinition = "text")
    private String labelHistory;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * PR-D USPS_DIRECT — reconciliation state for optimistic voids.
     * USPS APIs v3 have NO synchronous void endpoint (documented gotcha
     * #9 in docs/usps-direct-integration.md), so a click on "Void" for a
     * USPS_DIRECT order returns success immediately: {@code VoidService}
     * flips {@link #status} to {@code VOIDED} on the strength of a
     * connector-side "queued" verdict. This column is then updated
     * asynchronously by
     * {@code UspsDirectVoidReconciliationService.reconcile(csv)} once
     * the platform admin uploads USPS's eVS Refund report.
     *
     * <ul>
     *   <li>{@code NULL} — never reconciled (or not a void).</li>
     *   <li>{@code PENDING} — optimistic void queued, USPS unheard.</li>
     *   <li>{@code RECONCILED_APPROVED} — USPS refunded the postage;
     *       {@link #status} stays {@code VOIDED}.</li>
     *   <li>{@code RECONCILED_DENIED} — USPS refused the refund (label
     *       was scanned in transit is the common case);
     *       {@link #status} is flipped to {@code VOID_FAILED} and an
     *       operator toast is emitted.</li>
     * </ul>
     */
    @Column(name = "void_reconciliation_status", length = 30)
    private String voidReconciliationStatus;

    /**
     * PR-D USPS_DIRECT — timestamp of the last reconciliation check on
     * this row. NULL until the first eVS Refund report is uploaded and
     * the reconciliation service processes it.
     */
    @Column(name = "void_reconciliation_checked_at")
    private LocalDateTime voidReconciliationCheckedAt;
}
