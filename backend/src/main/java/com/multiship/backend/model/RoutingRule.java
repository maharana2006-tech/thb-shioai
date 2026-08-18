package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Sprint 44 — a per-client declarative routing rule. Rules run after
 * rate-shop / manual selection but before the label is generated: the
 * evaluator scans rules in {@link #priority} order (ASC = earlier),
 * first match wins.
 *
 * <p>Rule shape: any subset of the condition columns may be set.
 * Empty conditions match anything (a rule with nothing set is a
 * catch-all — used to gate an entire client to REROUTE or BLOCK).
 *
 * <h3>Conditions (all optional)</h3>
 * <ul>
 *   <li>Weight range: {@code minWeightLb} + {@code maxWeightLb}</li>
 *   <li>Destination: {@code destCountries} (CSV of ISO-2) and/or
 *       {@code destRegions} (CSV of region names)</li>
 *   <li>Current selection: {@code matchCarrier} + {@code matchServiceId}</li>
 *   <li>Declared value range: {@code minDeclaredValue} + {@code maxDeclaredValue}</li>
 *   <li>Order source: {@code matchOrderSource} — MANUAL / WMS / API / ERP</li>
 * </ul>
 *
 * <h3>Actions</h3>
 * <ul>
 *   <li>{@code REROUTE} — rewrite the selection to
 *       {@code targetServiceId} (carrier is inferred from the target).</li>
 *   <li>{@code BLOCK} — refuse label generation with {@code blockReason}.</li>
 * </ul>
 */
@Entity
@Table(name = "routing_rule",
        indexes = @Index(name = "idx_routing_rule_client", columnList = "client_code"))
@Data
public class RoutingRule {

    public enum ActionType { REROUTE, BLOCK }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Audit R2 #351 — @Version for optimistic locking. Two admins editing
     * the same rule simultaneously used to silently overwrite each other;
     * now the second save throws OptimisticLockingFailureException →
     * controller 409 ROUTING_RULE_CONCURRENT_EDIT so the FE can prompt
     * "refresh and retry". Column default 0 on backfilled rows.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Owning client (per-tenant only in v1 — no platform-wide rules). */
    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    /** Human label surfaced in the settings UI and dry-run trace. */
    @Column(nullable = false, length = 120)
    private String name;

    /** Lower = earlier evaluated. Ties broken by id ASC. */
    @Column(nullable = false)
    private Integer priority = 100;

    /** Inactive rules stay for history but are skipped by the evaluator. */
    @Column(nullable = false)
    private Boolean active = true;

    // ===== Conditions (all optional) =====

    @Column(name = "min_weight_lb", precision = 10, scale = 2)
    private BigDecimal minWeightLb;

    @Column(name = "max_weight_lb", precision = 10, scale = 2)
    private BigDecimal maxWeightLb;

    /** Comma-separated ISO-2 country codes (e.g. "DE,FR,IT"). Blank = any. */
    @Column(name = "dest_countries", length = 500)
    private String destCountries;

    /** Comma-separated region names (e.g. "Europe,Asia"). Blank = any. */
    @Column(name = "dest_regions", length = 300)
    private String destRegions;

    /** Match the currently-picked carrier (UPS / FEDEX / USPS / DHL). */
    @Column(name = "match_carrier", length = 20)
    private String matchCarrier;

    /** Match the currently-picked ShippingService.id. */
    @Column(name = "match_service_id")
    private Long matchServiceId;

    @Column(name = "min_declared_value", precision = 12, scale = 2)
    private BigDecimal minDeclaredValue;

    @Column(name = "max_declared_value", precision = 12, scale = 2)
    private BigDecimal maxDeclaredValue;

    /** MANUAL / WMS / API / ERP — matches Order.source. Blank = any. */
    @Column(name = "match_order_source", length = 20)
    private String matchOrderSource;

    /**
     * G2 — match the shipment's currently-picked warehouse. Null = any
     * warehouse. Combined with {@link #targetWarehouseId} lets a rule
     * express "if fulfilment is currently at warehouse=EAST → route to
     * FEDEX" (match-only) or "for dest=CA → fulfil from warehouse=WEST"
     * (target-only) or both.
     */
    @Column(name = "match_warehouse_id")
    private Long matchWarehouseId;

    // ===== Action =====

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private ActionType actionType = ActionType.REROUTE;

    /** For REROUTE — the new service to rewrite the selection to. Optional;
     *  a REROUTE with only {@link #targetWarehouseId} set will keep the
     *  original service and only swap the fulfilment warehouse. */
    @Column(name = "target_service_id")
    private Long targetServiceId;

    /**
     * G2 — for REROUTE, the fulfilment warehouse to rewrite the shipment
     * to. Null = don't change the warehouse. When set on a BLOCK rule the
     * evaluator ignores it (BLOCK actions have no target).
     */
    @Column(name = "target_warehouse_id")
    private Long targetWarehouseId;

    /** For BLOCK — human-readable reason surfaced to the operator. */
    @Column(name = "block_reason", length = 300)
    private String blockReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
