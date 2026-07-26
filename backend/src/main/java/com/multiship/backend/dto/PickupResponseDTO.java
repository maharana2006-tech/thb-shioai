package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Sprint 33 — wire response for {@code POST /api/v1/pickups}. Mirrors
 * {@link com.multiship.backend.service.carriers.CarrierConnector.PickupResult}
 * with wire-friendly types.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PickupResponseDTO {

    private String carrierCode;
    private String confirmationNumber;
    private LocalDate scheduledDate;
    private LocalTime pickupWindowStart;
    private LocalTime pickupWindowEnd;
    /** SCHEDULED | ERROR | NOT_SUPPORTED. */
    private String status;
    private String message;
}
