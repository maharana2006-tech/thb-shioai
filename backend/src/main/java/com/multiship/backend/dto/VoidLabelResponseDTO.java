package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for {@code POST /api/v1/orders/{n}/void}. Mirrors the
 * connector {@code VoidResult} record with the tracking number, void
 * outcome, human-readable message, and status enum.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoidLabelResponseDTO {

    private Integer orderNo;
    private String trackingNumber;
    private String carrierCode;

    /** True when the carrier confirmed the void. */
    private boolean voided;

    /** VOIDED | ALREADY_VOIDED | NOT_SUPPORTED | ERROR. */
    private String status;

    /** Operator-facing explanation. */
    private String message;
}
