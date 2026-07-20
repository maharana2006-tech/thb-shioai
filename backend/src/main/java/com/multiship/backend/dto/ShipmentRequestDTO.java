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
}
