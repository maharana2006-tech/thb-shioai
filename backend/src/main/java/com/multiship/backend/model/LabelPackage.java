package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One box (package) inside a multi-package shipment. Sprint 48 —
 * multi-package persistence. Every shipment (single or multi) has at
 * least one {@code label_package} row; a 3-box order has three.
 *
 * <p>Fields mirror {@link com.multiship.backend.dto.PackageDetailDTO}
 * on the wire side. Tracking + label URL are per-box: carriers that
 * return a tracking number per piece (UPS PackageResults, FedEx
 * pieceResponses) populate distinct values; carriers that only return
 * a master tracking (or where we haven't wired per-piece parsing yet)
 * repeat the master on every row.
 */
@Entity
@Table(name = "label_package",
        uniqueConstraints = @UniqueConstraint(name = "uk_label_package_order_seq",
                columnNames = {"order_no", "sequence_number"}))
// Sprint 48 N+1 fix — BatchSize enables Hibernate to fetch LazyProxies of
// this entity in batches of 100 when a list of Orders is loaded together.
// The composite unique constraint (order_no, sequence_number) already
// indexes the WHERE order_no = ? lookups the repository uses; no extra
// index annotation needed.
@org.hibernate.annotations.BatchSize(size = 100)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabelPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@code label_batch.order_no}. */
    @Column(name = "order_no", nullable = false)
    private Integer orderNo;

    /**
     * Sprint 48 B2 — FK to {@code shipment_batch.id}. Non-null for shipments
     * that went through the auto-split path (or were persisted after this
     * column was added); null on legacy rows so existing behaviour is
     * preserved.
     */
    @Column(name = "batch_id")
    private Long batchId;

    /** 1-based index within the shipment. */
    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    /** Per-box tracking number from the carrier (or the master when the carrier only returns one). */
    @Column(name = "tracking_number", length = 255)
    private String trackingNumber;

    /** Carrier's per-box tracking page URL when returned. */
    @Column(name = "tracking_url", columnDefinition = "text")
    private String trackingUrl;

    /** Per-box label artifact: a signed carrier URL or (since PR #550's
     *  LabelBytesPersister) the base64-encoded label bytes — same content
     *  class as order_label_tracking.label_file_path, widened by V31/V34. */
    @Column(name = "label_file_path", columnDefinition = "text")
    private String labelFilePath;

    @Column(name = "weight", precision = 12, scale = 4)
    private BigDecimal weight;

    /** LB | KG. */
    @Column(name = "weight_unit", length = 5)
    private String weightUnit;

    @Column(name = "length", precision = 8, scale = 2)
    private BigDecimal length;

    @Column(name = "width", precision = 8, scale = 2)
    private BigDecimal width;

    @Column(name = "height", precision = 8, scale = 2)
    private BigDecimal height;

    /** IN | CM. */
    @Column(name = "dim_unit", length = 5)
    private String dimUnit;

    /** Carrier packaging code (e.g. FEDEX_ENVELOPE, YOUR_PACKAGING). */
    @Column(name = "package_type", length = 60)
    private String packageType;

    @Column(name = "declared_value", precision = 12, scale = 2)
    private BigDecimal declaredValue;

    @Column(name = "reference", length = 255)
    private String reference;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
