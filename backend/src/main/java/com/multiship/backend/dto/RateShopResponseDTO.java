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

        /**
         * G4 — internal ShippingService.id resolved for this option, when a
         * matching row exists in the catalog. Null when the vendor's
         * serviceCode isn't in our catalog (rate-shop can still return the
         * option; the label path just won't be routing-aware).
         */
        private Long serviceId;

        /**
         * G4 — what the routing engine would do with this option at label
         * time. KEEP = no rule matches (silent OK). REROUTE = a rule would
         * rewrite service or warehouse. BLOCK = a rule would refuse
         * generation. Null when the caller didn't pass a client scope
         * (customerNo blank) or the option couldn't be resolved to an id.
         */
        private String routingOutcome;
        private String routingRuleName;
        /** Carrier of the rerouted service (REROUTE). */
        private String routingTargetCarrier;
        /** ServiceCode of the rerouted service (REROUTE). */
        private String routingTargetServiceCode;
        /** Warehouse the rule would swap to (REROUTE). */
        private Long routingTargetWarehouseId;
        /** BLOCK reason surfaced to the operator. */
        private String routingBlockReason;
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
