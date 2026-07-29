package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Per-client service-code alias: the ERP names the specific carrier service
 * with its own code (e.g. "F77-2DAY" for FEDEX_2DAY). Separate from
 * {@link ClientShipviaCodeMap} because "shipvia" is conceptually "how the
 * item moves" while "service code" is "which carrier product to buy" —
 * different fields on the ERP order.
 *
 * <p>Destination scope (country / region) lets the same ERP code resolve
 * to different services per lane. Uniqueness is enforced at the service
 * layer on {@code (clientCode, erpCode, destCountry, destRegion)}; the DB
 * doesn't declare a matching unique constraint because Postgres treats
 * NULLs in unique keys as distinct by default (see ClientShipviaCodeMap
 * doc for the same rationale).
 */
@Entity
@Table(name = "client_service_code_map",
        indexes = @Index(name = "idx_client_service_code_client", columnList = "client_code"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientServiceCodeMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    /** The raw service code the ERP sends (e.g. "F77-2DAY"). */
    @Column(name = "erp_code", nullable = false, length = 40)
    private String erpCode;

    /** FK to {@link ShippingService#getId()}. */
    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    /** ISO-2 destination country (nullable = "any country in the region"). */
    @Column(name = "dest_country", length = 2)
    private String destCountry;

    /** Destination region name (nullable = "any region"). */
    @Column(name = "dest_region", length = 40)
    private String destRegion;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
