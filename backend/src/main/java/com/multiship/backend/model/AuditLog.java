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

import java.time.LocalDateTime;

/**
 * Append-only audit trail across the settings write endpoints. Records
 * who did what to which entity, plus a snapshot / diff blob for
 * reconstruction on cascades (client disable stores the list of rows
 * it deactivated so re-enable can restore just those).
 *
 * <p>Not indexed by anything the app queries on in the hot path — the
 * settings UI reads this with pagination + optional filter, so a few
 * B-tree indexes on the common filter columns are enough.
 */
@Entity
@Table(name = "audit_log",
        indexes = {
                @Index(name = "idx_audit_log_ts", columnList = "created_at"),
                @Index(name = "idx_audit_log_entity", columnList = "entity_type, entity_id"),
                @Index(name = "idx_audit_log_actor", columnList = "actor"),
                @Index(name = "idx_audit_log_action", columnList = "action"),
                @Index(name = "idx_audit_log_client_code", columnList = "client_code"),
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Username of the operator who performed the action. Null for
     *  system-initiated actions (e.g. background jobs). */
    @Column(name = "actor", length = 100)
    private String actor;

    /**
     * CREATE, UPDATE, DELETE, TOGGLE_ACTIVE, CASCADE_DISABLE,
     * CASCADE_ENABLE. Free-string on purpose so new actions don't
     * require a schema change — the settings UI treats unknowns as
     * "OTHER" for the badge colour.
     */
    @Column(name = "action", nullable = false, length = 40)
    private String action;

    /**
     * Which domain the entity lives in — CLIENT, WAREHOUSE,
     * CARRIER_ACCOUNT, LABEL_TEMPLATE, ROUTING_RULE, CODE_MAP,
     * CUSTOMS_PROFILE, CLIENT_POLICY, CLIENT_MARKUP,
     * CLIENT_ALLOWED_SERVICE, CLIENT_ALLOWED_PACKAGE, etc. Used as
     * the top-level filter axis in the UI.
     */
    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    /** Numeric PK of the entity as a String so we don't need a
     *  separate column per entity id type. Null when the action is
     *  a create + the id isn't known yet (rare — most creates set
     *  this after save). */
    @Column(name = "entity_id", length = 100)
    private String entityId;

    /**
     * Human-readable identifier — client code, warehouse code,
     * carrier account number, etc. Rendered in the UI so the row
     * makes sense without joining back to the source table.
     */
    @Column(name = "entity_key", length = 200)
    private String entityKey;

    /**
     * JSON blob with details. Shape varies by action:
     * - CREATE: {"snapshot": {...}}
     * - UPDATE: {"before": {...}, "after": {...}} (optional; may just carry {"summary": "..."})
     * - DELETE: {"snapshot": {...}}
     * - CASCADE_DISABLE: {"cascadedRows": [{"entityType": "...", "entityId": "..."}, ...]}
     * - CASCADE_ENABLE: {"restoredRows": [{...}]}
     * Kept text to survive Postgres migration to JSONB later.
     */
    @Column(name = "changes", columnDefinition = "text")
    private String changes;

    /** Short free-text description ("Client ACME deactivated — 3 carrier accounts + 2 warehouses cascaded"). */
    @Column(name = "notes", length = 500)
    private String notes;

    /**
     * Tenant scope for per-row filtering. NULL = system-initiated event
     * (background job / migration) — visible only to platform operators.
     * Set from the acting user's {@code users.client_code} at write time
     * (see {@link com.multiship.backend.service.AuditService#record}).
     */
    @Column(name = "client_code", length = 64)
    private String clientCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
