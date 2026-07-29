package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Sprint 33 — request body for {@code POST /api/v1/pickups}. Schedules
 * a courier pickup at a specific address on a specific date within a
 * time window; carrier is picked by {@code carrierCode}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PickupRequestDTO {

    /** UPS | FEDEX | USPS | DHL. Case-insensitive. */
    @NotBlank
    private String carrierCode;

    /** Optional — prefer the customer's own carrier account when set. */
    private String customerNo;

    @NotNull
    private LocalDate pickupDate;

    /** Start of the pickup window (local time). */
    private LocalTime pickupWindowStart;

    /** End of the pickup window (local time). */
    private LocalTime pickupWindowEnd;

    @NotBlank
    private String contactName;

    @NotBlank
    private String contactPhone;

    @NotBlank
    private String addressLine1;

    private String addressLine2;

    @NotBlank
    private String city;

    private String state;

    @NotBlank
    private String postalCode;

    @NotBlank
    private String countryCode;

    /** Number of parcels the driver should collect. */
    @Positive
    private int packageCount;

    /** Total weight across all parcels. */
    @NotNull
    @Positive
    private BigDecimal totalWeight;

    /** LB | KG. Null defaults to LB. */
    private String weightUnit;

    /** Free-form notes for the driver. */
    private String specialInstructions;
}
