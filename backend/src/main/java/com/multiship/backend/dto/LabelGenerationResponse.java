package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabelGenerationResponse {

    private Long orderNo;
    private String carrierCode;
    private String carrierName;
    private String carrierAccountCode;
    private String tenantId;
    private String trackingNumber;
    private String trackingUrl;
    private String labelUrl;
    private String labelPdf;
    /**
     * Response status token — one of:
     * {@code GENERATED} (sync label success), {@code QUEUED} (PR-G1: single
     * USPS_DIRECT label routed to the 55/hr queue), {@code QUEUED_MPS}
     * (PR-G1: MPS order fanned into N queue rows), {@code REJECTED}
     * (PR-G1: intl-MPS refused before enqueue), plus the legacy
     * pre-flight states ({@code CHOOSE_ACCOUNT}, {@code NEEDS_DETAILS},
     * {@code CLIENT_MISSING}, {@code CUSTOMS_REQUIRED}, {@code ERROR}).
     */
    private String status;
    private BigDecimal shippingCost;
    private LocalDateTime estimatedDelivery;
    private String message;

    /** Which source shipped this label: ORDER, REFERENCE, or DEFAULT. */
    private String accountSource;

    // ===== 3PL snapshot (mirror of the columns stored on OrderTracking) =====

    /** Warehouse the shipment shipped from — null for ad-hoc / no-client shipments. */
    private String warehouseCode;
    /** Carrier's rate before markup — mirrors {@link #shippingCost} when no markup applies. */
    private BigDecimal carrierAmount;
    /** {@link #carrierAmount} + client markup. */
    private BigDecimal billableAmount;
    /** PERCENT | FLAT. */
    private String markupKind;
    private BigDecimal markupValue;
    /** ISO-4217 of {@link #carrierAmount} and {@link #billableAmount}. */
    private String markupCurrency;
    /** True when the label was created past the client's cutoff — see OrderTracking. */
    private Boolean dispatchNextBusinessDay;

    // ===== Scenario 2: generation paused, order needs carrier details =====

    /** True when the order's carrier details are partial and must be completed first. */
    private Boolean needsDetails;

    /** Set when status=CLIENT_MISSING: the unregistered client code to prefill the add-client form. */
    private String clientCode;
    /** Fields the user still has to provide (accountNumber / clientId / clientSecret). */
    private List<String> missingFields;
    private String prefillAccountNumber;
    private String prefillCarrierCode;
    private String prefillClientId;
    private String prefillEnvironment;

    // ===== PR-G1 (USPS_DIRECT routing) — queue routing shape =====

    /**
     * PR-G1 — server-assigned id of the {@code usps_label_queue} row this
     * label was enqueued under. Populated when {@link #status} is
     * {@code QUEUED}; the FE uses it to render "queued — item #123" and
     * to key backpressure polling. Null for sync {@code GENERATED} labels
     * and for the MPS batch shape (see {@link #mpsPieceCount}).
     */
    private Long queueItemId;

    /**
     * PR-G1 — number of queue rows persisted when a multi-piece USPS
     * order fans through {@code UspsMpsSplitterService}. Populated when
     * {@link #status} is {@code QUEUED_MPS}; the FE uses it to mount the
     * MPS progress card. Null for single-label enqueue (see
     * {@link #queueItemId}) and for sync {@code GENERATED} labels.
     */
    private Integer mpsPieceCount;
}
