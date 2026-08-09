package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO projection of {@link com.multiship.backend.model.LabelPackage} —
 * one row per box in a shipment. Nested inside
 * {@link OrderWithLinesDTO#getPackages()} and consumed by the label
 * document page / ZPL renderer to render per-package tracking, weight,
 * and dimensions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabelPackageDTO {
    private Integer sequenceNumber;
    private String trackingNumber;
    private String trackingUrl;
    private String labelFilePath;
    private BigDecimal weight;
    private String weightUnit;
    private BigDecimal length;
    private BigDecimal width;
    private BigDecimal height;
    private String dimUnit;
    private String packageType;
    private BigDecimal declaredValue;
    private String reference;
    private String description;
}
