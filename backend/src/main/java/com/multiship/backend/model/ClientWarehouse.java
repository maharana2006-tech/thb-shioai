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
 * Client ↔ Warehouse many-to-many link with a per-client default flag.
 *
 * unique(client_code, warehouse_id) — a client can't attach the same
 * warehouse twice. Only one link per client may carry isDefault=true; the
 * "one default per client" rule is enforced in the service layer because
 * JPA @Index has no partial-index syntax.
 *
 * When the caller doesn't specify a warehouse on a shipment, the resolution
 * service picks the default (this row where isDefault=true).
 */
@Entity
@Table(name = "client_warehouse",
        uniqueConstraints = @UniqueConstraint(name = "uq_client_warehouse_client_wh",
                columnNames = {"client_code", "warehouse_id"}),
        indexes = {
                @Index(name = "idx_client_warehouse_client", columnList = "client_code"),
                @Index(name = "idx_client_warehouse_wh", columnList = "warehouse_id"),
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientWarehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    /** The client's default ship-from. At most one row per client is true. */
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
