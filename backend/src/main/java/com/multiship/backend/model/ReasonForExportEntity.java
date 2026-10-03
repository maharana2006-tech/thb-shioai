package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** V117 — reason-for-export (SHIPPING_PURPOSES) registry. */
@Entity
@Table(name = "reason_for_export")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReasonForExportEntity {

    @Id
    @Column(length = 32, nullable = false)
    private String code;

    @Column(nullable = false, length = 120)
    private String label;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
