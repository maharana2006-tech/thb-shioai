package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One entry in the client's allowed-package list. Flattens the underlying
 * PackagePreset summary so the settings UI can render each row without a
 * follow-up catalog fetch.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientAllowedPackageDTO {
    private Long id;
    private String clientCode;
    private Long presetId;

    // PackagePreset summary.
    private String name;
    /** CARRIER | CUSTOM */
    private String kind;
    private String carrier;
    private String carrierPackageCode;
    private String originCountry;
    /** DOMESTIC | INTERNATIONAL | BOTH */
    private String scope;
    private BigDecimal length;
    private BigDecimal width;
    private BigDecimal height;
    private String dimUnit;
    private BigDecimal maxWeight;
    private String weightUnit;

    /** True on the row picked when a shipment doesn't name a package. */
    private Boolean isDefault;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
