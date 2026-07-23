package com.multiship.backend.dto.external;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Available service options for a route. */
@Data
@Builder
public class ExternalRateResponse {
    private List<Option> options;
    /** True only when live carrier rate pricing is wired for this environment. */
    private boolean pricingAvailable;
    private String note;

    @Data
    @Builder
    public static class Option {
        private String carrier;
        private String serviceCode;
        private String serviceName;
        private String scope;
        /** Null when live pricing is not available. */
        private BigDecimal estimatedAmount;
        private String currency;
    }
}
