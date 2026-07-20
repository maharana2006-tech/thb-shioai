package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * Allowed package for a carrier service ("1 carrier → service → multiple
 * packages"): links a ShippingService to a PackagePreset, optionally with the
 * negotiated discount %. At label time the smallest linked package whose max
 * weight fits the order is auto-picked; services with no links fall back to
 * the global default preset.
 */
@Entity
@Table(name = "service_package",
        uniqueConstraints = @UniqueConstraint(name = "uq_service_package",
                columnNames = {"service_id", "preset_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServicePackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    @Column(name = "preset_id", nullable = false)
    private Long presetId;
}
