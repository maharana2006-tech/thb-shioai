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
 * Allowed-packages tag on a {@link ShipViaMapping} rule: the rule may be
 * used to ship parcels packaged into one of the listed presets.
 *
 * <p>Empty child-set semantics: when a rule has no rows here, it's
 * unrestricted at the rule level (labelling still respects the client's
 * {@link ClientAllowedPackage} allowlist and the {@link PackagePreset}
 * ownership cascade). Non-empty means the rule acts as the DEFAULT set the
 * background/auto-shipping path picks from; users can still override at
 * label time.
 *
 * <p>{@code rule_id} FK is a plain long so a bulk delete when the parent
 * rule goes away is a one-shot query.
 */
@Entity
@Table(name = "ship_method_rule_package",
        uniqueConstraints = @UniqueConstraint(name = "uq_rule_package",
                columnNames = {"rule_id", "preset_id"}),
        indexes = {
                @Index(name = "idx_rule_package_rule", columnList = "rule_id"),
                @Index(name = "idx_rule_package_preset", columnList = "preset_id"),
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipMethodRulePackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@link ShipViaMapping#getId()}. */
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    /** FK to {@link PackagePreset#getId()}. */
    @Column(name = "preset_id", nullable = false)
    private Long presetId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
