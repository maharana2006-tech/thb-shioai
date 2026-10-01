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

/** V112 — platform-wide carrier registry (Auth Gap-6-A). */
@Entity
@Table(name = "carriers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierPlatformEntity {

    public static final String MODE_LIVE = "LIVE";
    public static final String MODE_TEST = "TEST";

    @Id
    @Column(name = "carrier_code", length = 32, nullable = false)
    private String carrierCode;

    @Column(name = "display_name", length = 80)
    private String displayName;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(nullable = false, length = 8)
    private String mode = MODE_LIVE;

    /** USPS | FEDEX | UPS | DHL. Rolls up STAMPS_COM + USPS_DIRECT under USPS. */
    @Column(length = 16)
    private String family;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
