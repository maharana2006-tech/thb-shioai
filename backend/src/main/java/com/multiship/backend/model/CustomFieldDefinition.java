package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Sprint 43 — per-tenant custom field definition. Each tenant defines
 * its own set of fields (PO number, department, marketplace order id,
 * etc.); values are stored per order in {@link OrderCustomFieldValue}.
 *
 * <p>Scoped by {@link #tenantId} (matches {@code Client.clientCode}).
 * A null tenantId means platform-wide — surfaces on every tenant's
 * order forms. The {@link #fieldKey} is the machine-readable key
 * used in payloads; {@link #label} is what users see.
 */
@Entity
@Table(name = "custom_field_definitions",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"tenant_id", "field_key"},
                name = "uk_custom_field_tenant_key"))
@Data
public class CustomFieldDefinition {

    public enum FieldType { TEXT, NUMBER, DATE, SELECT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning tenant (client code); null = platform-wide field. */
    @Column(name = "tenant_id", length = 50)
    private String tenantId;

    /** snake_case identifier used in API payloads (e.g. "po_number"). */
    @Column(name = "field_key", nullable = false, length = 60)
    private String fieldKey;

    /** Human-friendly label shown on forms and order detail. */
    @Column(nullable = false, length = 120)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_type", nullable = false, length = 20)
    private FieldType fieldType = FieldType.TEXT;

    /**
     * For SELECT type: comma-separated option list (e.g. "S,M,L").
     * Ignored for other types.
     */
    @Column(name = "select_options", length = 500)
    private String selectOptions;

    /** True when the field must have a value before an order can commit. */
    @Column(nullable = false)
    private Boolean required = false;

    /** Rendering order on the form; lower = higher on the page. */
    @Column(name = "position", nullable = false)
    private Integer position = 100;

    /** Soft-hide flag — kept for historical values but not editable/required. */
    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
