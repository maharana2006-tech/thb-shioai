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
 * Origin-warehouse tag on a {@link ShipViaMapping} rule: the rule may only be
 * used when the order ships from one of the listed warehouses.
 *
 * <p>Empty child-set semantics: when a rule has no rows here, it's unrestricted
 * at the rule level and matches any origin warehouse. Non-empty narrows the
 * rule to just the listed warehouse ids — mirrors the pattern used by
 * {@link ShipMethodRulePackage} for allowed packages.
 *
 * <p>Historical note: {@link ShipViaMapping#getWarehouseId()} is a single
 * (deprecated) origin-warehouse column that predates multi-warehouse rules.
 * The join table is the current source of truth; the single-column value is
 * retained only for backwards compatibility with in-flight rows and is not
 * exposed on new / edited rules from the UI.
 */
@Entity
@Table(name = "ship_method_rule_warehouse",
        uniqueConstraints = @UniqueConstraint(name = "uq_rule_warehouse",
                columnNames = {"rule_id", "warehouse_id"}),
        indexes = {
                @Index(name = "idx_rule_warehouse_rule", columnList = "rule_id"),
                @Index(name = "idx_rule_warehouse_warehouse", columnList = "warehouse_id"),
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipMethodRuleWarehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@link ShipViaMapping#getId()}. */
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    /** FK to {@link Warehouse#getId()}. */
    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
