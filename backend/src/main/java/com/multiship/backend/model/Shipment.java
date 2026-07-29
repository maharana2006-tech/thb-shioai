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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Sprint 47 — one physical label / package. Introduced for the multi-warehouse
 * split path where a single {@link ShipmentGroup} spawns N shipments (one
 * per warehouse).
 *
 * <p>Existing single-shipment flows still write their tracking to
 * {@link Order#getTrack()}; they DO NOT touch this table (yet). Only the
 * multi-warehouse path persists here in Sprint 47. A follow-up sprint may
 * migrate single-shipment flows to Shipment for uniformity.
 */
@Entity
@Table(name = "shipment",
        indexes = {
                @Index(name = "idx_shipment_group", columnList = "group_id"),
                @Index(name = "idx_shipment_order", columnList = "order_no"),
                @Index(name = "idx_shipment_tracking", columnList = "tracking_number"),
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@link ShipmentGroup#getId()}. Always set for multi-warehouse
     *  shipments; null when a Shipment row is created outside a group
     *  (reserved for future single-shipment migration). */
    @Column(name = "group_id")
    private Long groupId;

    /** Parent order (mirrors ShipmentGroup.orderNo when present). */
    @Column(name = "order_no")
    private Integer orderNo;

    @Column(name = "client_code", length = 50)
    private String clientCode;

    /** Warehouse this shipment ships FROM. Every shipment in a group has a
     *  different warehouseCode; this is what "split" means. */
    @Column(name = "warehouse_code", nullable = false, length = 50)
    private String warehouseCode;

    @Column(name = "carrier_code", length = 20)
    private String carrierCode;

    @Column(name = "service_code", length = 50)
    private String serviceCode;

    /** Tracking number the carrier returned. */
    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    @Column(name = "tracking_url", length = 500)
    private String trackingUrl;

    @Column(name = "label_url", length = 500)
    private String labelUrl;

    /** Base64 label bytes (short-lived when labelUrl is populated). */
    @Column(name = "label_pdf", columnDefinition = "text")
    private String labelPdf;

    /** Carrier's rate before client markup. */
    @Column(name = "carrier_amount", precision = 12, scale = 4)
    private BigDecimal carrierAmount;

    /** carrier_amount + client markup. */
    @Column(name = "billable_amount", precision = 12, scale = 4)
    private BigDecimal billableAmount;

    /** ISO-4217. */
    @Column(name = "currency", length = 3)
    private String currency;

    /** CREATED | ERROR | VOIDED. */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "CREATED";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
