package com.multiship.backend.service.carriers.usps.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One commodity line inside a USPS v3 {@link UspsCustomsForm}.
 *
 * <p>Mirrors the shape USPS's {@code /international-labels/v3/label}
 * endpoint expects under {@code customsForm.commodities[]}. Field names
 * match the USPS wire vocabulary exactly so the connector serialises the
 * object graph directly without a rename pass.
 *
 * <p><b>REGULATORY_REFERENCE.</b> Since 2025-09-01 USPS enforces the
 * international HS-6 tariff mandate — every commodity on an outbound
 * international shipment must carry at least a 6-digit
 * {@link #hsTariffNumber}. Missing → 400 rejection at USPS with an
 * opaque error body. See <a
 * href="https://about.usps.com/newsroom/national-releases/2025/hs6-mandate.htm">USPS
 * 2025 International HS-6 Tariff Mandate</a> (also mirrors WCO
 * Harmonized System §1.1). The
 * {@link com.multiship.backend.service.carriers.usps.UspsCustomsFormBuilder}
 * enforces the guard at connector-boundary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UspsCommodity {

    /** Free-form commodity description (e.g. "Cotton t-shirt"). Required by USPS. */
    private String description;

    /** Integer quantity. Required. */
    private Integer quantity;

    /** Per-unit weight in the parent shipment's weight unit (USPS wants LB). */
    private BigDecimal weight;

    /** Per-unit customs value in the parent shipment's currency. */
    private BigDecimal value;

    /**
     * Harmonized System / tariff code, 6-digit minimum. USPS enforces this
     * per the 2025-09-01 HS-6 mandate — see class javadoc.
     */
    private String hsTariffNumber;

    /** ISO-3166 alpha-2 country of origin. Required by USPS on intl labels. */
    private String countryOfOrigin;

    /**
     * Unit-of-measure code (e.g. "EA" each, "KG" kilogram, "PC" piece).
     * Optional — omitted when unknown.
     */
    private String unitOfMeasure;
}
