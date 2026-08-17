package com.multiship.backend.dto;

import com.multiship.backend.model.CarrierShippingLimit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Sprint 52 admin UI — read shape for a {@code carrier_shipping_limit}
 * row. Mirrors {@link com.multiship.backend.model.CarrierShippingLimit}
 * one-to-one so the admin table can render every column, including the
 * server-set {@code effectiveFrom} / {@code effectiveUntil} timeline.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierShippingLimitResponse {

    private Long id;
    private String carrierCode;
    private String serviceCode;
    private String scope;
    private String direction;
    private Integer maxPackages;
    private Integer maxCommodities;
    private BigDecimal maxTotalWeightLb;
    private BigDecimal freeDeclaredValue;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveUntil;
    private Boolean active;
    private String notes;

    public static CarrierShippingLimitResponse from(CarrierShippingLimit e) {
        return CarrierShippingLimitResponse.builder()
                .id(e.getId())
                .carrierCode(e.getCarrierCode())
                .serviceCode(e.getServiceCode())
                .scope(e.getScope())
                .direction(e.getDirection())
                .maxPackages(e.getMaxPackages())
                .maxCommodities(e.getMaxCommodities())
                .maxTotalWeightLb(e.getMaxTotalWeightLb())
                .freeDeclaredValue(e.getFreeDeclaredValue())
                .effectiveFrom(e.getEffectiveFrom())
                .effectiveUntil(e.getEffectiveUntil())
                .active(e.getActive())
                .notes(e.getNotes())
                .build();
    }
}
