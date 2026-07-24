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
}
