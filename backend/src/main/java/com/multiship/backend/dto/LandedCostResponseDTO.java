package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Wire response for {@code POST /api/v1/landed-cost}. Mirrors the
 * connector {@link com.multiship.backend.service.carriers.CarrierConnector.LandedCostResult}
 * with a wire-friendly line-item DTO. Sprint 32.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LandedCostResponseDTO {

    private String carrierCode;
    /** LIVE | NOT_SUPPORTED | ERROR. */
    private String source;
    private BigDecimal freightAmount;
    private BigDecimal dutyTotal;
    private BigDecimal taxTotal;
    private BigDecimal otherTotal;
    private BigDecimal grandTotal;
    private String currency;
    private List<LandedCostLine> lineItems;
    private List<String> warnings;
    private String message;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LandedCostLine {
        private String description;
        private String hsCode;
        private Integer quantity;
        private BigDecimal declaredValue;
        private BigDecimal dutyAmount;
        private BigDecimal taxAmount;
        private String currency;
    }
}
