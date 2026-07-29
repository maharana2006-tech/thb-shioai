package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Sprint 47 — link row that stitches N per-warehouse {@link Shipment} rows
 * together as one logical multi-warehouse shipment.
 *
 * <p>A single group corresponds to one operator action: "generate labels
 * for order X across warehouses A, B, C." Every child {@link Shipment}
 * carries this group's id so reports, webhooks, and the UI can render the
 * shipments as a set instead of standalone rows.
 *
 * <p>{@link #orderNo} is optional — the external API + ad-hoc flows can
 * fire a multi-warehouse call without a parent Order (partners send raw
 * shipment payloads). Internal flows always populate it.
 */
@Entity
@Table(name = "shipment_group",
        indexes = {
                @Index(name = "idx_shipment_group_client", columnList = "client_code"),
                @Index(name = "idx_shipment_group_order", columnList = "order_no"),
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning client. */
    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    /** Parent order the split came from. Null for ad-hoc / external
     *  multi-warehouse calls with no upstream Order row. */
    @Column(name = "order_no")
    private Integer orderNo;

    /** Number of child shipments in the group — denormalised for cheap
     *  list-view rendering. */
    @Column(name = "shipment_count", nullable = false)
    @Builder.Default
    private Integer shipmentCount = 0;

    /** Operator or ApiKey principal that triggered the split. Null on
     *  legacy / anonymous flows. */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
