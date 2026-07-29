package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Sprint 43 — value of a {@link CustomFieldDefinition} on a specific
 * order. One row per (orderNo, fieldKey); the value is stored as a
 * string and parsed on read according to the definition's type.
 *
 * <p>{@link #fieldKey} is denormalised from the definition to keep
 * value reads cheap (a single join-less lookup) and to survive a
 * definition rename without an implicit data migration.
 */
@Entity
@Table(name = "order_custom_field_values",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"order_no", "field_key"},
                name = "uk_order_custom_field"))
@Data
public class OrderCustomFieldValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false)
    private Integer orderNo;

    /** Denormalised from the definition; keeps single-order reads cheap. */
    @Column(name = "field_key", nullable = false, length = 60)
    private String fieldKey;

    /** Raw value. TEXT/SELECT store as-is; NUMBER/DATE store canonical strings. */
    @Column(name = "field_value", columnDefinition = "text")
    private String fieldValue;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
