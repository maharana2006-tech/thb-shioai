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
 * Per-client allowlist of carrier services. Nothing is visible to a client
 * until an admin adds a row here; that's the 3PL "explicit assignment"
 * model chosen in the design phase.
 *
 * unique(client_code, service_id) — no duplicate assignments.
 * At most one isDefault=true per client (service-layer enforced): that's
 * the service used when a shipment call doesn't name one.
 */
@Entity
@Table(name = "client_allowed_service",
        uniqueConstraints = @UniqueConstraint(name = "uq_client_allowed_service",
                columnNames = {"client_code", "service_id"}),
        indexes = {
                @Index(name = "idx_client_allowed_service_client", columnList = "client_code"),
                @Index(name = "idx_client_allowed_service_svc", columnList = "service_id"),
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientAllowedService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    /** The client's default service. At most one row per client is true. */
    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
