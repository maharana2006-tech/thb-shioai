package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A ship-from location. First-class in a 3PL setup so a client can have
 * multiple origins (their own warehouses) AND the 3PL can host shared
 * fulfilment warehouses that many clients pull from.
 *
 * ownerType splits the two:
 *   PLATFORM — the 3PL owns the location; any client may be attached via
 *              {@link ClientWarehouse}. ownerClientCode is null.
 *   CLIENT   — the location belongs to one client (their own warehouse);
 *              ownerClientCode names them. Only that client can be linked.
 *
 * The client↔warehouse many-to-many + which one is default lives in
 * {@link ClientWarehouse}. Enforced on write in the service layer.
 */
@Entity
@Table(name = "warehouse",
        indexes = {
                @Index(name = "idx_warehouse_code", columnList = "code", unique = true),
                @Index(name = "idx_warehouse_owner_client", columnList = "owner_client_code"),
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Warehouse {

    public static final String OWNER_PLATFORM = "PLATFORM";
    public static final String OWNER_CLIENT = "CLIENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Short human code — e.g. 3PL-EAST, ACME-BLR-01. Unique across the platform. */
    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    /** Ship-from postal address. Uses Address's default column names. */
    @Embedded
    private Address address;

    /** PLATFORM | CLIENT. Determines visibility + who may attach it. */
    @Column(name = "owner_type", nullable = false, length = 10)
    @Builder.Default
    private String ownerType = OWNER_PLATFORM;

    /** Populated only when ownerType = CLIENT; null for PLATFORM warehouses. */
    @Column(name = "owner_client_code", length = 50)
    private String ownerClientCode;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public boolean isPlatformOwned() {
        return OWNER_PLATFORM.equalsIgnoreCase(ownerType);
    }
}
