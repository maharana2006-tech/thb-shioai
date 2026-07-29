package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sprint 47 — response for the split-shipment endpoint. Contains the
 * ShipmentGroup identifier that stitches the children together plus one
 * {@link ChildShipment} per warehouse that was actually shipped.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiWarehouseLabelResponse {

    private Long groupId;
    private String clientCode;
    private Integer orderNo;

    /** Number of child shipments generated (matches {@code shipments.size()}). */
    private Integer shipmentCount;

    private List<ChildShipment> shipments;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChildShipment {
        private Long shipmentId;
        private String warehouseCode;
        private String carrierCode;
        private String serviceCode;
        private String trackingNumber;
        private String trackingUrl;
        private String labelUrl;
        private String labelPdf;
        private BigDecimal carrierAmount;
        private BigDecimal billableAmount;
        private String currency;
        /** CREATED | ERROR. */
        private String status;
        /** Human-readable message from the underlying generator. */
        private String message;
        /** Number of source lines that funnelled into this child. */
        private Integer lineCount;
    }
}
