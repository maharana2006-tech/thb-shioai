package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * PR-Printer-R7a — how many physical copies of the commercial invoice
 * to print for a given (client, carrier). See V70 for the DDL and
 * {@code InvoiceCopiesService.resolveCopies} for the fallback chain.
 *
 * <p>{@code clientCode} is nullable: {@code NULL} means "tenant-wide
 * default for this carrier", overridden by any explicit per-client row.
 */
@Entity
@Table(name = "invoice_copies")
@Getter
@Setter
@NoArgsConstructor
public class InvoiceCopies {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Uppercased client code; NULL = tenant-wide default row for this carrier. */
    @Column(name = "client_code", length = 50)
    private String clientCode;

    /** Uppercased carrier code — matches the CarrierAccountRef / Connections vocabulary. */
    @Column(name = "carrier_code", length = 30, nullable = false)
    private String carrierCode;

    /** Bounded 1..20 at the DB via CHECK; validated again at the service layer. */
    @Column(name = "copies", nullable = false)
    private Integer copies;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
