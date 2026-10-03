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

/**
 * C4 — one row per carrier code the system recognises. {@code sourceCode}
 * is the value that appears anywhere (ship_via_cd, carrier_account_ref.
 * carrier_code, FE dropdowns); {@code targetCode} is the canonical
 * carrier it maps to. For a canonical row, source and target are equal.
 */
@Entity
@Table(name = "carrier_alias")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierAliasEntity {

    @Id
    @Column(name = "source_code", length = 40)
    private String sourceCode;

    @Column(name = "target_code", nullable = false, length = 40)
    private String targetCode;

    @Column(name = "display_label", nullable = false, length = 60)
    private String displayLabel;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;
}
