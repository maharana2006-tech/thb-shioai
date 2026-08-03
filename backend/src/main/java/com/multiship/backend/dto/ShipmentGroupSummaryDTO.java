package com.multiship.backend.dto;

import com.multiship.backend.model.ShipmentGroup;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Sprint 47 — list view of a {@link ShipmentGroup}. Excludes the child
 * shipments so list responses stay bounded; callers who need the details
 * follow the id to {@code GET /api/v1/shipment-groups/{id}}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentGroupSummaryDTO {

    private Long id;
    private String clientCode;
    private Integer orderNo;
    private Integer shipmentCount;
    private String createdBy;
    private LocalDateTime createdAt;

    public static ShipmentGroupSummaryDTO from(ShipmentGroup g) {
        return ShipmentGroupSummaryDTO.builder()
                .id(g.getId())
                .clientCode(g.getClientCode())
                .orderNo(g.getOrderNo())
                .shipmentCount(g.getShipmentCount())
                .createdBy(g.getCreatedBy())
                .createdAt(g.getCreatedAt())
                .build();
    }
}
