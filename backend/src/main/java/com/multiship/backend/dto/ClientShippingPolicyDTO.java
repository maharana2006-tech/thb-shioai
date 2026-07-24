package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Per-client rate-shopping + SLA config. Returned by GET; the same shape is
 * accepted by PUT.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientShippingPolicyDTO {
    private String clientCode;
    /** CHEAPEST | FASTEST | FIXED. */
    private String rateStrategy;
    /** Populated only when rateStrategy=FIXED. */
    private Long fixedServiceId;
    /** Local cutoff (HH:mm:ss). Null = no cutoff configured. */
    private LocalTime cutoffTime;
    /** IANA zone id, e.g. America/New_York. */
    private String cutoffTz;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
