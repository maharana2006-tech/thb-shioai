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

import java.time.LocalDateTime;

/**
 * One warehouse tag on a {@link ClientAllowedService} row: the client may
 * only use the service when shipping FROM one of the listed warehouses.
 *
 * <p>Empty child-set semantics: when a {@code ClientAllowedService} has no
 * rows in this table, the service is allowed at <em>any</em> warehouse
 * attached to the client — the unrestricted default. Adding at least one
 * row switches the row to whitelist mode: only the listed warehouses may
 * fulfil this service.
 *
 * <p>Mirrors {@link ClientAllowedServiceDestination}: {@code client_code}
 * is denormalised from the parent's row so the DB can enforce the unique
 * key cheaply and so query filters can skip the join to the parent.
 */
@Entity
@Table(name = "client_allowed_service_warehouse",
        uniqueConstraints = @UniqueConstraint(name = "uq_client_allowed_svc_wh",
                columnNames = {"allowed_service_id", "warehouse_id"}),
        indexes = {
                @Index(name = "idx_client_allowed_svc_wh_svc", columnList = "allowed_service_id"),
                @Index(name = "idx_client_allowed_svc_wh_client", columnList = "client_code"),
                @Index(name = "idx_client_allowed_svc_wh_wh", columnList = "warehouse_id"),
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientAllowedServiceWarehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@link ClientAllowedService#getId()} — plain long so bulk delete
     *  when the parent goes away is a one-shot query. */
    @Column(name = "allowed_service_id", nullable = false)
    private Long allowedServiceId;

    /** Denormalised from the parent for constraint indexing. */
    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    /** FK to {@link Warehouse#getId()}. */
    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
