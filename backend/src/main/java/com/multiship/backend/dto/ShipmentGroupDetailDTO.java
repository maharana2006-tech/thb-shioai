package com.multiship.backend.dto;

import com.multiship.backend.model.Shipment;
import com.multiship.backend.model.ShipmentGroup;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Sprint 47 — detail view of a {@link ShipmentGroup} with every child
 * {@link Shipment} nested underneath. Omits {@code labelPdf} (base64 blob)
 * to keep responses bounded — callers that need the raw label follow
 * {@link ShipmentDTO#getLabelUrl()}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentGroupDetailDTO {

    private Long id;
    private String clientCode;
    private Integer orderNo;
    private Integer shipmentCount;
    private String createdBy;
    private LocalDateTime createdAt;
    private List<ShipmentDTO> shipments;

    public static ShipmentGroupDetailDTO from(ShipmentGroup g, List<Shipment> children) {
        return ShipmentGroupDetailDTO.builder()
                .id(g.getId())
                .clientCode(g.getClientCode())
                .orderNo(g.getOrderNo())
                .shipmentCount(g.getShipmentCount())
                .createdBy(g.getCreatedBy())
                .createdAt(g.getCreatedAt())
                .shipments(children.stream().map(ShipmentDTO::from).toList())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShipmentDTO {
        private Long id;
        private String warehouseCode;
        private String carrierCode;
        private String serviceCode;
        private String trackingNumber;
        private String trackingUrl;
        private String labelUrl;
        private BigDecimal carrierAmount;
        private BigDecimal billableAmount;
        private String currency;
        private String status;
        private LocalDateTime createdAt;

        public static ShipmentDTO from(Shipment s) {
            return ShipmentDTO.builder()
                    .id(s.getId())
                    .warehouseCode(s.getWarehouseCode())
                    .carrierCode(s.getCarrierCode())
                    .serviceCode(s.getServiceCode())
                    .trackingNumber(s.getTrackingNumber())
                    .trackingUrl(s.getTrackingUrl())
                    .labelUrl(s.getLabelUrl())
                    .carrierAmount(s.getCarrierAmount())
                    .billableAmount(s.getBillableAmount())
                    .currency(s.getCurrency())
                    .status(s.getStatus())
                    .createdAt(s.getCreatedAt())
                    .build();
        }
    }
}
