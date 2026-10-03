package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

/**
 * V127 — packaging allowlist sidecar for {@link ClientShipviaCodeMap}.
 * Mirrors the deleted {@code ship_method_rule_package} from the pre-merge
 * SSM table: one row per (map_id, preset_id). Empty = unrestricted.
 * Row FK cascades on delete of the parent map row.
 */
@Entity
@Table(name = "client_shipvia_code_map_package")
@IdClass(ClientShipviaCodeMapPackage.PK.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientShipviaCodeMapPackage {

    @Id
    @Column(name = "map_id", nullable = false)
    private Long mapId;

    @Id
    @Column(name = "preset_id", nullable = false)
    private Long presetId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PK implements Serializable {
        private Long mapId;
        private Long presetId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK other)) return false;
            return Objects.equals(mapId, other.mapId)
                    && Objects.equals(presetId, other.presetId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mapId, presetId);
        }
    }
}
