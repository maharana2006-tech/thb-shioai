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
 * Per-client shipvia alias: maps an ERP-side ship-method code straight to a
 * platform {@link ShippingService}. Distinct from {@link ShipViaMapping} in
 * that this is a direct alias (no rules, no destination filter) — used at
 * order intake to translate the raw code into the canonical service.
 *
 * <p>ShipViaMapping stays for rule-based resolution when the ERP hasn't
 * pre-decided the exact service.
 */
@Entity
@Table(name = "client_shipvia_code_map",
        uniqueConstraints = @UniqueConstraint(name = "uq_client_shipvia_code",
                columnNames = {"client_code", "erp_code"}),
        indexes = @Index(name = "idx_client_shipvia_code_client", columnList = "client_code"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientShipviaCodeMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    /** The raw ship-method code the ERP sends (e.g. "P80"). */
    @Column(name = "erp_code", nullable = false, length = 40)
    private String erpCode;

    /** FK to {@link ShippingService#getId()}. */
    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
