package com.multiship.backend.model;

import com.multiship.backend.service.output.DocType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Sprint 52 — always-on DB copy of every generated shipment document.
 *
 * <p>Normalised into its own table (rather than BYTEA columns on
 * shipment / order) so the label bytes live in their own TOAST heap:
 * a 3PL shipping millions of labels a year would otherwise bloat
 * every {@code SELECT * FROM shipment} scan. The FK-style
 * {@link #shipmentId} is a plain BIGINT (not a JPA relationship) —
 * legacy single-shipment flows persist tracking on {@code order.trk}
 * with no {@link Shipment} row; storing the raw ID keeps this table
 * usable for both paths.
 *
 * <p>Retrieval via
 * {@code shipmentDocumentRepository.findByShipmentIdAndDocType(...)}
 * or by orderNo.
 */
@Entity
@Table(name = "shipment_document",
        indexes = {
                @Index(name = "idx_shipdoc_shipment", columnList = "shipment_id, doc_type"),
                @Index(name = "idx_shipdoc_order",    columnList = "order_no"),
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The shipment this document belongs to. NOT a JPA FK — see class
     * javadoc for why. May be 0 or a synthetic value when the caller
     * only knows the orderNo (legacy single-shipment path).
     */
    @Column(name = "shipment_id", nullable = false)
    private Long shipmentId;

    /**
     * Order number for cross-lookup — useful for legacy flows that
     * don't materialise a Shipment row. Nullable.
     */
    @Column(name = "order_no")
    private Integer orderNo;

    @Column(name = "client_code", length = 64)
    private String clientCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 32)
    private DocType docType;

    /**
     * MIME hint — {@code application/pdf} for CI + laser labels,
     * {@code application/vnd.zebra.zpl} for raw ZPL. Nullable when
     * the caller doesn't know; consumers should sniff bytes then.
     */
    @Column(name = "content_type", length = 64)
    private String contentType;

    /**
     * Raw document bytes. Postgres stores {@code bytea} in-line up to
     * ~2KB then spills to TOAST; label PDFs (~50-500KB) always TOAST.
     */
    @Column(name = "bytes", nullable = false)
    private byte[] bytes;

    /**
     * Cached size — cheap to compute at write time and avoids loading
     * the whole {@code bytes} column when a list endpoint just wants
     * to show "12KB" in a table.
     */
    @Column(name = "byte_size", nullable = false)
    private int byteSize;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now(ZoneOffset.UTC);
        if (bytes != null) byteSize = bytes.length;
    }
}
