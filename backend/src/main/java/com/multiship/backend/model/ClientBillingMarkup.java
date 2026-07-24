package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Per-client billing markup applied on top of the carrier rate to compute
 * the billable amount stamped on the shipment. One row per client (client
 * without a row = zero markup).
 *
 * PERCENT — value is a percentage (e.g. 12.5000 = 12.5%).
 * FLAT    — value is a currency amount added per shipment.
 *
 * value is DECIMAL(12,4); we round-per-shipment when applied and snapshot
 * both kind + value on the shipment record so historical bills stay stable
 * even if the client's markup changes later.
 */
@Entity
@Table(name = "client_billing_markup")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientBillingMarkup {

    public static final String KIND_PERCENT = "PERCENT";
    public static final String KIND_FLAT = "FLAT";

    /** client_code is the natural PK; one markup row per client. */
    @Id
    @Column(name = "client_code", length = 50)
    private String clientCode;

    /** PERCENT | FLAT. */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String kind = KIND_PERCENT;

    @Column(nullable = false, precision = 12, scale = 4)
    @Builder.Default
    private BigDecimal value = BigDecimal.ZERO;

    /** ISO-4217. Used to validate against the carrier rate currency at apply time. */
    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "USD";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
