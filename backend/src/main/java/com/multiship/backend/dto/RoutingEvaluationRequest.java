package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Sprint 44 — synthetic shipment input for the dry-run endpoint and for
 * the in-flow evaluator. All fields are optional so callers can preview
 * with partial data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingEvaluationRequest {
    /** Total shipment weight, converted to LB by the caller. */
    private BigDecimal weightLb;
    private String destCountry;   // ISO-2
    private String destRegion;    // canonical region name (from countries.ts taxonomy)
    private String currentCarrier;
    private Long currentServiceId;
    /** G2 — the warehouse currently resolved for the shipment; null when
     *  the caller hasn't resolved one (ad-hoc shipments). */
    private Long currentWarehouseId;
    private BigDecimal declaredValue;
    private String orderSource;   // MANUAL | WMS | API | ERP
}
