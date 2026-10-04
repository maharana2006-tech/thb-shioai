package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

/** V120 — per-carrier customs-clearance vocabulary (duties payer / Incoterm). */
@Entity
@Table(name = "carrier_clearance_option")
@IdClass(CarrierClearanceOptionEntity.PK.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierClearanceOptionEntity {

    @Id @Column(length = 32, nullable = false) private String carrier;
    @Id @Column(length = 64, nullable = false) private String code;

    @Column(nullable = false, length = 120) private String label;
    @Column(name = "sort_order", nullable = false) @Builder.Default private Integer sortOrder = 0;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PK implements Serializable {
        private String carrier;
        private String code;
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(carrier, pk.carrier) && Objects.equals(code, pk.code);
        }
        @Override public int hashCode() { return Objects.hash(carrier, code); }
    }
}
