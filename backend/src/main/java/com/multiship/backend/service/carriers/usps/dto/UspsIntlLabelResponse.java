package com.multiship.backend.service.carriers.usps.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Typed-shell reference for USPS's international-labels response.
 *
 * <p>Populated by
 * {@link com.multiship.backend.service.carriers.UspsDirectConnector#parseIntlLabelResponse}
 * from the raw JSON USPS returns. Kept as a static POJO (unlike the
 * request side) so callers can address {@link #customsFormImage} without
 * a map lookup — it's the one field that differs materially from the
 * domestic response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UspsIntlLabelResponse {

    /** USPS Impb tracking number (13-digit). */
    private String trackingNumber;

    /** Total postage. */
    private BigDecimal postage;

    /** Mail class returned in labelMetadata. */
    private String mailClass;

    /** Rate zone (1-9 international). */
    private String zone;

    /** Base64 label image (PDF or ZPL depending on request.imageType). */
    private String labelImage;

    /**
     * Base64 CN22 / CN23 / PS-2976-A composite (or single-form when
     * value permits CN22 alone). USPS returns this alongside the label
     * on the intl endpoint; kept separately from the label so callers
     * can print / attach it independently.
     */
    private String customsFormImage;

    /** Raw JSON response for the audit trail. */
    private String rawResponse;
}
