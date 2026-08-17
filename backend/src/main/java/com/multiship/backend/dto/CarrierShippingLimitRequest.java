package com.multiship.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Sprint 52 admin UI — create / update payload for the
 * {@code carrier_shipping_limit} row an operator manages from the
 * {@code /settings/carrier-limits} page. Field constraints mirror the
 * column definitions in {@link com.multiship.backend.model.CarrierShippingLimit}
 * so the UI never posts a value the DB will reject.
 *
 * <p>{@code effectiveFrom} is server-set on create (to now); updates keep
 * the original {@code effectiveFrom} — operators supersede a row by
 * inserting a new one, not editing the timeline in-place.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierShippingLimitRequest {

    /** UPS | FEDEX | DHL | STAMPS (canonical carrier code). */
    @NotBlank(message = "carrierCode is required")
    @Size(max = 32, message = "carrierCode must be 32 chars or fewer")
    private String carrierCode;

    /** Specific service code (FEDEX_ENVELOPE, FEDEX_GROUND, ...). Null =
     *  carrier default. */
    @Size(max = 60, message = "serviceCode must be 60 chars or fewer")
    private String serviceCode;

    /** DOMESTIC | INTERNATIONAL | BOTH. */
    @NotBlank(message = "scope is required")
    @Pattern(regexp = "BOTH|DOMESTIC|INTERNATIONAL",
            message = "scope must be BOTH, DOMESTIC or INTERNATIONAL")
    private String scope;

    /** FORWARD | RETURN | null (matches any direction). */
    @Pattern(regexp = "FORWARD|RETURN",
            message = "direction must be FORWARD or RETURN when set")
    private String direction;

    /** Max pieces per carrier shipment call (1..9999). */
    @NotNull(message = "maxPackages is required")
    @Min(value = 1, message = "maxPackages must be >= 1")
    @Max(value = 9999, message = "maxPackages must be <= 9999")
    private Integer maxPackages;

    /** Max commodity lines per shipment. Nullable — resolver falls back
     *  to 999 when unset. */
    @Min(value = 1, message = "maxCommodities must be >= 1")
    @Max(value = 9999, message = "maxCommodities must be <= 9999")
    private Integer maxCommodities;

    /** Max total shipment weight in LB. */
    @DecimalMin(value = "0.01", message = "maxTotalWeightLb must be > 0")
    private BigDecimal maxTotalWeightLb;

    /** Declared-value threshold below which the carrier charges NO
     *  additional coverage. */
    @DecimalMin(value = "0.00", message = "freeDeclaredValue must be >= 0")
    private BigDecimal freeDeclaredValue;

    /** Whether the row is active. Defaults to true when null. */
    private Boolean active;

    /** Free-text operator note. */
    @Size(max = 500, message = "notes must be 500 chars or fewer")
    private String notes;
}
