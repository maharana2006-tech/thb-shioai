package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

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

    /**
     * Sprint 43 — tenant-defined custom field values on the order
     * (fieldKey -> string value). Populated on single-order reads;
     * omitted from list responses to keep them cheap.
     */
    private Map<String, String> customFields;

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
        /** Where the order came from: MANUAL | WMS | API | ERP. */
        private String source;
        /** The WMS's own order number, as sent on the external shipment request. */
        private String refOrderNumber;
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