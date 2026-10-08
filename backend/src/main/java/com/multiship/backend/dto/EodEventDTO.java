package com.multiship.backend.dto;

import com.multiship.backend.model.CarrierEodLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * V135 — one row of the Pickups & End-of-Day activity table (never returns the
 * JPA entity directly).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EodEventDTO {

    private Long id;
    /** PICKUP | CLOSEOUT */
    private String kind;
    private String carrierCode;
    private String accountNumber;
    private String customerNo;
    private String warehouseCode;
    private LocalDate eventDate;
    private String reference;
    private int trackingCount;
    private String status;
    private String message;
    /** MANUAL | SCHEDULED */
    private String source;
    private String createdBy;
    private LocalDateTime createdAt;

    public static EodEventDTO from(CarrierEodLog e) {
        return EodEventDTO.builder()
                .id(e.getId())
                .kind(e.getKind())
                .carrierCode(e.getCarrierCode())
                .accountNumber(e.getAccountNumber())
                .customerNo(e.getCustomerNo())
                .warehouseCode(e.getWarehouseCode())
                .eventDate(e.getEventDate())
                .reference(e.getReference())
                .trackingCount(e.getTrackingCount())
                .status(e.getStatus())
                .message(e.getMessage())
                .source(e.getSource())
                .createdBy(e.getCreatedBy())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
