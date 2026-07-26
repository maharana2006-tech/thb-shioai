package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Sprint 34 — request body for {@code POST /api/v1/manifests}. Closes
 * out a day's shipments at the carrier and returns the manifest ID +
 * PDF the driver signs.
 *
 * <p>When {@code trackingNumbers} is null / empty, the backend
 * auto-populates from {@code order_label_tracking} rows created between
 * {@code closeDate 00:00} and {@code closeDate 23:59} for the resolved
 * customer/platform account.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManifestRequestDTO {

    /** UPS | FEDEX | USPS. DHL returns NOT_SUPPORTED. Case-insensitive. */
    @NotBlank
    private String carrierCode;

    /** Optional — prefer the customer's own carrier account when set. */
    private String customerNo;

    /**
     * Tracking numbers to include on the manifest. Non-empty; supply the
     * list explicitly for MVP.
     */
    @NotEmpty
    private List<String> trackingNumbers;

    /** Close date (defaults to today when null on the wire). */
    private LocalDate closeDate;

    /** Optional ship-from address for the manifest header. */
    private String addressName;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    private String countryCode;
}
