package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentRequestDTO {

    @NotBlank
    private String carrierCode;

    private String accountNumber;

    @NotBlank
    private String serviceType;

    @NotBlank
    private String packageType;

    @NotNull
    @Positive
    private BigDecimal weight;

    @Positive
    private BigDecimal length;

    @Positive
    private BigDecimal width;

    @Positive
    private BigDecimal height;

    @NotBlank
    private String shipperName;

    @NotBlank
    private String shipperPhone;

    @NotBlank
    private String shipperAddressLine1;

    private String shipperAddressLine2;

    @NotBlank
    private String shipperCity;

    @NotBlank
    private String shipperState;

    @NotBlank
    private String shipperPostalCode;

    @NotBlank
    private String shipperCountryCode;

    @NotBlank
    private String recipientName;

    @NotBlank
    private String recipientPhone;

    @NotBlank
    private String recipientAddressLine1;

    private String recipientAddressLine2;

    @NotBlank
    private String recipientCity;

    @NotBlank
    private String recipientState;

    @NotBlank
    private String recipientPostalCode;

    @NotBlank
    private String recipientCountryCode;

    private String referenceNumber;
    private String specialInstructions;
    private BigDecimal declaredValue;

    /**
     * ISO-4217 currency for {@link #declaredValue}. Also the default currency
     * when {@link #intl} has commodities without their own currency. Nullable
     * for backwards compat — callers that don't set it get USD downstream,
     * mirroring the pre-existing FedEx hardcoded default. New callers should
     * always populate this (Sprint 1 populator does, from OrderCustoms.currency).
     */
    private String declaredValueCurrency;

    /**
     * Unit for {@link #weight}. LB | KG. Null means "assume LB" so legacy
     * callers still work. Connectors that speak the unit natively (UPS,
     * FedEx) pass it as-is; USPS via SWSIM must convert to ounces.
     */
    private String weightUnit;

    /**
     * Unit for {@link #length}, {@link #width}, {@link #height}. IN | CM.
     * Null means "assume IN". Same conversion rules as weightUnit.
     */
    private String dimUnit;

    /**
     * International customs / importer / broker / duties block. Null on
     * domestic shipments. When non-null, connectors are expected to add the
     * customs declaration to their carrier payload; Sprint 1 populates it but
     * connectors haven't wired it up yet (see feature/intl-sprint-N branches).
     */
    private IntlShipmentBlockDTO intl;
}
