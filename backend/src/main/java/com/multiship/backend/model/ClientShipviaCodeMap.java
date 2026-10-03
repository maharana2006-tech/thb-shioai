package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Shipvia alias: maps an ERP-side ship-method code (e.g. "P80") to a
 * platform {@link ShippingService}, with optional scope by client,
 * origin warehouse, and destination country/region.
 *
 * <p>Pre-merge (through V125) this was a strictly per-client table and
 * global rules lived in the parallel {@code shipvia_service_mapping}
 * table. V126 relaxed {@code client_code} to nullable so one row can
 * represent either a per-client alias OR a platform-wide rule, and
 * added {@code warehouse_id} to carry the origin-scoping that used to
 * live on the deleted SSM sidecar. Packaging allowlist (another SSM
 * sidecar's job) now hangs off the composite-key
 * {@code client_shipvia_code_map_package} table — the field below is a
 * Jackson round-trip for the API only (persisted by the service layer).
 *
 * <p>Resolver specificity ladder (most specific wins):
 * client+warehouse+country &gt; client+warehouse+any &gt; client+any+country
 * &gt; client+any+any &gt; global+country &gt; global+any.
 */
@Entity
@jakarta.persistence.EntityListeners(ImportRevalidationListener.class)
@Table(name = "client_shipvia_code_map",
        indexes = @Index(name = "idx_client_shipvia_code_client", columnList = "client_code"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientShipviaCodeMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null = platform-wide rule (applies to any client). */
    @Column(name = "client_code", length = 50)
    private String clientCode;

    /** The raw ship-method code the ERP sends (e.g. "P80"). */
    @Column(name = "erp_code", nullable = false, length = 40)
    private String erpCode;

    /** FK to {@link ShippingService#getId()}. */
    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    /** V98 — when true, intake treats this ERP code as an "on hold"
     *  marker and blocks the order. */
    @Column(name = "is_hold", nullable = false)
    @Builder.Default
    private Boolean isHold = Boolean.FALSE;

    /** ISO-2 destination country (nullable = "any country in the region"). */
    @Column(name = "dest_country", length = 2)
    private String destCountry;

    /** Destination region name (e.g. "North America"; nullable = "any region"). */
    @Column(name = "dest_region", length = 40)
    private String destRegion;

    /** V126 — origin warehouse scope (nullable = any origin). Add multiple
     *  rows if the same ERP code should route from more than one warehouse
     *  (deliberately simpler than SSM's list-sidecar — the list was never
     *  used in practice). */
    @Column(name = "warehouse_id")
    private Long warehouseId;

    /** V127 — packaging allowlist persisted via the
     *  {@code client_shipvia_code_map_package} sidecar. Round-trip only on
     *  the API; the service layer handles the sidecar writes.
     *  Empty = unrestricted. */
    @Transient
    private List<Long> allowedPresetIds;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
