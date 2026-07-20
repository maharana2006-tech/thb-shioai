package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponseDTO {
    private OrderDetails orderDetails;
    private ShippingDetails shippingDetails;
    private LabelDetails labelDetails;
    private ErrorDetails errorDetails;

    /**
     * Which carrier account the generation cascade would pick for this order
     * (scenario + account + prefill fields). Populated only when the list is
     * requested with includeResolution=true.
     */
    private OrderAccountResolutionDTO accountResolution;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderDetails {
        private Integer orderNo;
        private Integer orderSuffix;
        private String status;
        private String customerCode;
        private String goodsDescription;
        private LocalDate createdDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShippingDetails {
        private String city;
        private String state;
        private String zipCode;
        private String shipVia;
        private BigDecimal weight;
        private String shipViaDescription;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LabelDetails {
        private String status;
        private Boolean isGenerated;
        private String trackingNumber;
        private String trackingUrl;
        private String labelFilePath;
        private LocalDateTime generatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorDetails {
        private Boolean hasError;
        private String errorMessage;
    }
}