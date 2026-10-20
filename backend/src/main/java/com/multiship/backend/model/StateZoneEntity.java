package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * G2 — country + state → IANA zone id. Drives DST-aware offset
 * resolution for timestamps that carriers consume (FedEx
 * readyDateTimestamp today; UPS / DHL EOD slots when they land).
 * {@code zone_id} is an IANA zone string ("America/New_York"),
 * never a fixed offset — callers resolve the offset at the
 * specific pickup/ship moment via
 * {@link java.time.ZoneId#getRules()} so DST transitions handle
 * themselves.
 */
@Entity
@Table(name = "state_zone")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StateZoneEntity {

    @EmbeddedId
    private StateZoneId id;

    @Column(name = "zone_id", nullable = false, length = 60)
    private String zoneId;

    @Column(name = "display_name", length = 80)
    private String displayName;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    @Embeddable
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StateZoneId implements Serializable {
        @Column(name = "country_code", length = 2, nullable = false)
        private String countryCode;

        @Column(name = "state_code", length = 10, nullable = false)
        private String stateCode;
    }
}
