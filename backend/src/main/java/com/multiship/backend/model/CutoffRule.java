package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * G7 — one rule row in the (source × carrier × warehouse) cutoff matrix.
 * A rule with any of the three key fields null means "any" — so a row
 * with all three null applies to every shipment. Multiple rules can
 * co-exist; the {@code CutoffShiftService} treats a shipment as
 * past-cutoff if ANY active rule matches AND the current time in the
 * rule's timezone is past its {@link #cutoffTime}.
 *
 * <p>Timezone precedence: rule's timezone → client's timezone → UTC.
 */
@Entity
@Table(name = "cutoff_rule")
@Data
public class CutoffRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** MANUAL | BULK | API | WMS | DTC | null (any). */
    @Column(name = "source", length = 20)
    private String source;

    /** FEDEX | UPS | USPS | DHL | STAMPS_COM | USPS_DIRECT | null (any). */
    @Column(name = "carrier_code", length = 20)
    private String carrierCode;

    /** Null = any warehouse. */
    @Column(name = "warehouse_id")
    private Long warehouseId;

    /** Local time on the rule's timezone. */
    @Column(name = "cutoff_time", nullable = false)
    private LocalTime cutoffTime = LocalTime.of(20, 0);

    /** IANA tz identifier. Null = fall back to Client.timezone. */
    @Column(name = "timezone", length = 64)
    private String timezone;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 200)
    private String updatedBy;
}
