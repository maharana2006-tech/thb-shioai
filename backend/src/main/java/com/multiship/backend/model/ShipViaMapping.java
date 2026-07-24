package com.multiship.backend.model;

import jakarta.persistence.Column;
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
 * A SHIP-METHOD RULE (the ShipStation "service mapping" pattern): an order's
 * ship method (the ERP shipvia/connect code) resolves to a carrier service,
 * optionally narrowed by CLIENT, ORIGIN WAREHOUSE, and DESTINATION (country
 * or region). Multiple rules may exist for one code; the most specific match
 * wins:
 *   client+warehouse+country > client+warehouse+any > client+any+country
 *   > client+any+any > global+…+country > global+…+any.
 *
 * clientCode null = any client. warehouseId null = any warehouse (so a
 * client's rules can be origin-agnostic when they only ship from one
 * location). destType ANY|REGION|COUNTRY (null = ANY); destValue holds the
 * region name or ISO country code.
 * (Table name kept from the original single-mapping version.)
 */
@Entity
@Table(name = "shipvia_service_mapping",
        indexes = {
                @Index(name = "idx_shipvia_rule_code", columnList = "shipvia_cd"),
                @Index(name = "idx_shipvia_rule_warehouse", columnList = "warehouse_id"),
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipViaMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The ERP connect code exactly as it appears on orders (P80, F77, L01…). */
    @Column(name = "shipvia_cd", nullable = false, length = 20)
    private String shipviaCd;

    /** Narrow to one client; null = applies to any client. */
    @Column(name = "client_code", length = 50)
    private String clientCode;

    /** Narrow to one origin warehouse; null = applies to any warehouse. */
    @Column(name = "warehouse_id")
    private Long warehouseId;

    /**
     * ANY | COUNTRIES (null = ANY). Legacy values REGION/COUNTRY from the
     * first iteration still match (single value in destValue).
     */
    @Column(name = "dest_type", length = 10)
    private String destType;

    /**
     * For COUNTRIES: the destination "zone" — a space-separated set of ISO
     * codes ("DE FR US"), built in the UI via regions and/or individual
     * countries (the ShipperHQ zone pattern: one or more countries per zone).
     */
    @Column(name = "dest_value", columnDefinition = "TEXT")
    private String destValue;

    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
