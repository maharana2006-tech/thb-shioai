package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

/** V120 — per-carrier label-format vocabulary. See migration for scope. */
@Entity
@Table(name = "carrier_label_format")
@IdClass(CarrierLabelFormatEntity.PK.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierLabelFormatEntity {

    @Id @Column(length = 32, nullable = false)
    private String carrier;

    @Id @Column(length = 64, nullable = false)
    private String code;

    @Column(nullable = false, length = 120)
    private String label;

    @Column(name = "is_stock_type", nullable = false)
    private Boolean isStockType = false;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

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
