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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One carrier service level (UPS Ground, FedEx International Priority…).
 * The catalog is seeded once and the admin toggles what the platform offers —
 * the ShipStation pattern: everything exists, disabled services disappear
 * from resolution. scope: DOMESTIC | INTERNATIONAL | BOTH — service selection
 * uses COUNTRY inequality (a DE→FR parcel still rides an international
 * service even though it clears no customs).
 */
@Entity
@Table(name = "shipping_service",
        uniqueConstraints = @UniqueConstraint(name = "uq_shipping_service_code",
                columnNames = {"carrier", "service_code"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShippingService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Canonical carrier: UPS | FEDEX | USPS. */
    @Column(nullable = false, length = 20)
    private String carrier;

    /** The code the carrier's API expects (UPS "03", FedEx "FEDEX_GROUND"…). */
    @Column(name = "service_code", nullable = false, length = 40)
    private String serviceCode;

    @Column(nullable = false, length = 100)
    private String name;

    /** DOMESTIC | INTERNATIONAL | BOTH. */
    @Column(nullable = false, length = 15)
    private String scope;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
