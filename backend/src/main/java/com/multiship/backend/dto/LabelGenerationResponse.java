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
