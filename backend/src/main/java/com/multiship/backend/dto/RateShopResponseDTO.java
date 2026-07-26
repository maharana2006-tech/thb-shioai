package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response envelope for the rate-shop endpoint. Carries the merged +
 * sorted list of rate options across every carrier that responded, plus a
 * per-carrier status list so the UI can show "UPS returned 4 options,
 * FedEx returned 0 (no live credentials), DHL timed out" instead of just
 * a bare list.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateShopResponseDTO {

    /** Merged options across every carrier, sorted cheapest first. */
    private List<RateOptionDTO> options;

    /** Per-carrier status so callers can distinguish "returned nothing"
     *  from "no credentials configured" from "call failed". */
    private List<CarrierRateStatus> carrierResults;

    /** Wire-shape of one priced service option — mirrors
     *  {@link com.multiship.backend.service.carriers.CarrierConnector.RateOption}
     *  with the same field set so callers can bind one JSON payload to
     *  either type. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateOptionDTO {
        private String carrierCode;
        private String serviceCode;
        private String serviceName;
        private BigDecimal totalAmount;
        private String currency;
        private LocalDateTime estimatedDelivery;
        private Integer transitDays;
    }

    /** Per-carrier fan-out result. {@code source} is LIVE | STUB | ERROR;
     *  matches the Sprint 16 tracking response's freshness badge convention. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CarrierRateStatus {
        private String carrierCode;
        private int optionCount;
        /** LIVE | STUB | ERROR. STUB when no credentials were configured for
         *  this carrier on the resolving client; ERROR when the connector
         *  threw or returned an unexpected response shape. */
        private String source;
        /** Human-readable explanation — "4 options from UPS",
         *  "no credentials configured for FedEx", "DHL rate call failed". */
        private String message;
    }
}
