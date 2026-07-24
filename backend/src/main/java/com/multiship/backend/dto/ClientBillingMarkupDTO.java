package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Per-client billing markup. Snapshotted onto every shipment at label time
 * so historical bills stay stable if the client's markup later changes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientBillingMarkupDTO {
    private String clientCode;
    /** PERCENT | FLAT. */
    private String kind;
    /** DECIMAL(12,4). */
    private BigDecimal value;
    /** ISO-4217. */
    private String currency;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
