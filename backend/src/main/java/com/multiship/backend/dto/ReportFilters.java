package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Sprint 45 — unified filter object for the reporting API. Every field
 * is optional; a null value means "no filter on this dimension".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportFilters {
    private LocalDateTime from;
    private LocalDateTime to;
    private String customerNo;
    private String carrier;
    private String service;
    private String status;
    private String originCountry;
    private String destCountry;
    private BigDecimal minWeightLb;
    private BigDecimal maxWeightLb;
    private BigDecimal minDeclaredValue;
    private BigDecimal maxDeclaredValue;
}
