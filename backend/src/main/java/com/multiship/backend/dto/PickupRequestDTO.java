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

    /**
     * DHL-8 — default box dimensions the driver expects to collect (applied
     * per package on the pickup wire). Pre-fix, {@link
     * com.multiship.backend.service.carriers.DhlConnector#generateDhlPickupPackages}
     * hardcoded 30 × 20 × 10 cm for every package on the DHL pickup body,
     * which drove DHL's routing to the wrong vehicle class for larger
     * parcels. All 4 fields are optional so callers that haven't been
     * updated for DHL-8 keep working; the connector falls back to the
     * historical 30 × 20 × 10 cm default when any of the length/width/
     * height are unset.
     */
    private BigDecimal defaultLength;
    private BigDecimal defaultWidth;
    private BigDecimal defaultHeight;

    /** CM | IN. Null defaults to CM (matches DHL's expected default). */
    private String dimUnit;

    /** Free-form notes for the driver. */
    private String specialInstructions;

    /**
     * FDX-F — carrier service the pickup covers. Determines which driver
     * fleet the carrier dispatches:
     * <ul>
     *   <li>{@code EXPRESS} — FedEx Express (FDXE) / UPS Air (007). Time-
     *       sensitive parcels; different driver than Ground.</li>
     *   <li>{@code GROUND} — FedEx Ground (FDXG) / UPS Ground (003).
     *       Default when unset — matches the pre-FDX-F hardcode.</li>
     *   <li>{@code INTERNATIONAL} — same as EXPRESS for FedEx/UPS but
     *       kept distinct so the FE can surface an intl-only picker for
     *       operators shipping across borders.</li>
     * </ul>
     * DHL Express only offers one product (P) and SWSIM has no per-request
     * service code; both connectors accept the field but no-op on it.
     * Null falls to GROUND.
     */
    private String pickupServiceType;
}
