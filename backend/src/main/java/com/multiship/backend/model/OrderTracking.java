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

    @Column(name = "error_message")
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

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}