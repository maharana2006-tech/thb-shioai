package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Audit sidecar for the ERP codes an order came in with. Order intake
 * translates each raw code via the client's alias map and writes the
 * canonical value onto the {@link Order} row; the untranslated original
 * lives here so an admin can always trace back "what did the ERP actually
 * send us".
 *
 * <p>Keyed by {@code order_no} (natural PK) — one row per order.
 */
@Entity
@Table(name = "order_raw_codes",
        indexes = @Index(name = "idx_order_raw_codes_client", columnList = "client_code"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderRawCodes {

    @Id
    @Column(name = "order_no")
    private Integer orderNo;

    /** Client the order was for — denormalised for the reporting index. */
    @Column(name = "client_code", length = 50)
    private String clientCode;

    /** The raw ship-method code the ERP sent (may equal the canonical). */
    @Column(name = "raw_shipvia", length = 40)
    private String rawShipvia;

    /** The raw service-level code (nullable — only some ERPs send this). */
    @Column(name = "raw_service_code", length = 40)
    private String rawServiceCode;

    /** The raw destination-country string ("USA", "United Kingdom", …). */
    @Column(name = "raw_dest_country", length = 40)
    private String rawDestCountry;

    /** The raw package SKU (e.g. "ACME-BOX-A"). */
    @Column(name = "raw_package_code", length = 40)
    private String rawPackageCode;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
