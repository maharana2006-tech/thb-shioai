package com.multiship.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * V135 — request body for {@code POST /api/v1/manifests/close-day}. Closes out
 * a whole day's open labels for one carrier without the caller listing every
 * tracking number: the backend gathers the day's GENERATED, non-voided labels
 * for that carrier itself. Powers the Settings → Pickups & End-of-Day page and
 * is the same path the scheduled CARRIER_CLOSEOUT job uses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloseDayRequestDTO {

    /** UPS | FEDEX | USPS. DHL / USPS-Direct return NOT_SUPPORTED. */
    @NotBlank
    private String carrierCode;

    /** Optional — only this client/tenant's labels; blank = every client. */
    private String customerNo;

    /** Optional — only this warehouse's labels; blank = every warehouse. */
    private String warehouseCode;

    /** Close date; null = today. */
    private LocalDate closeDate;
}
