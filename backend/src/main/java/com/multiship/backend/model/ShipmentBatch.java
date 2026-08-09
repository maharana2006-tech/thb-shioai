package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One physical carrier call within an order. Sprint 48 B2 — when a shipment
 * exceeds a carrier's MPS cap (e.g. FedEx 40 pkg limit but the order has
 * 100 boxes), we split into multiple carrier calls; each call becomes one
 * {@code shipment_batch} row with its own master tracking + label.
 *
 * <p>Single-cap-fitting shipments still get one batch row (batch_seq=1).
 * Every {@link LabelPackage} FK-references the batch it belongs to via
 * {@code label_package.batch_id} — nullable for rows persisted before this
 * table existed.
 */
@Entity
@Table(name = "shipment_batch",
        indexes = @Index(name = "idx_shipment_batch_order", columnList = "order_no"),
        uniqueConstraints = @UniqueConstraint(name = "uk_shipment_batch_order_seq",
                columnNames = {"order_no", "batch_seq"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false)
    private Integer orderNo;

    /** 1-based within the order. batch 1..N corresponds to sub-request 1..N. */
    @Column(name = "batch_seq", nullable = false)
    private Integer batchSeq;

    @Column(name = "carrier_code", length = 20)
    private String carrierCode;

    /** The carrier's master tracking for THIS batch (FedEx piece 1's tracking,
     *  UPS ShipmentIdentificationNumber, DHL shipmentTrackingNumber, Stamps
     *  piece 1). Different batch → different master. */
    @Column(name = "master_tracking_number", length = 255)
    private String masterTrackingNumber;

    @Column(name = "master_tracking_url", length = 500)
    private String masterTrackingUrl;

    @Column(name = "master_label_url", length = 500)
    private String masterLabelUrl;

    @Column(name = "master_label_pdf", columnDefinition = "text")
    private String masterLabelPdf;

    @Column(name = "package_count_in_batch", nullable = false)
    private Integer packageCountInBatch;

    @Column(name = "shipping_cost", precision = 12, scale = 4)
    private BigDecimal shippingCost;

    @Column(name = "raw_response", columnDefinition = "text")
    private String rawResponse;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
