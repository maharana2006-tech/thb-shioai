package com.multiship.backend.dto;

import com.multiship.backend.model.RoutingRule;
import com.multiship.backend.model.RoutingRule.ActionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Sprint 44 — CRUD DTO for a routing rule. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingRuleDTO {

    private Long id;
    private String clientCode;
    private String name;
    private Integer priority;
    private Boolean active;

    // Conditions
    private BigDecimal minWeightLb;
    private BigDecimal maxWeightLb;
    private String destCountries;
    private String destRegions;
    private String matchCarrier;
    private Long matchServiceId;
    private BigDecimal minDeclaredValue;
    private BigDecimal maxDeclaredValue;
    private String matchOrderSource;
    /** G2 — match on the currently-resolved warehouse. Null = any. */
    private Long matchWarehouseId;

    // Action
    private ActionType actionType;
    private Long targetServiceId;
    /** G2 — for REROUTE, the target warehouse. Null = don't change. */
    private Long targetWarehouseId;
    private String blockReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static RoutingRuleDTO from(RoutingRule r) {
        return RoutingRuleDTO.builder()
                .id(r.getId()).clientCode(r.getClientCode())
                .name(r.getName()).priority(r.getPriority()).active(r.getActive())
                .minWeightLb(r.getMinWeightLb()).maxWeightLb(r.getMaxWeightLb())
                .destCountries(r.getDestCountries()).destRegions(r.getDestRegions())
                .matchCarrier(r.getMatchCarrier()).matchServiceId(r.getMatchServiceId())
                .minDeclaredValue(r.getMinDeclaredValue()).maxDeclaredValue(r.getMaxDeclaredValue())
                .matchOrderSource(r.getMatchOrderSource())
                .matchWarehouseId(r.getMatchWarehouseId())
                .actionType(r.getActionType()).targetServiceId(r.getTargetServiceId())
                .targetWarehouseId(r.getTargetWarehouseId())
                .blockReason(r.getBlockReason())
                .createdAt(r.getCreatedAt()).updatedAt(r.getUpdatedAt())
                .build();
    }

    public RoutingRule toEntity() {
        RoutingRule r = new RoutingRule();
        r.setId(id);
        r.setClientCode(clientCode);
        r.setName(name);
        r.setPriority(priority == null ? 100 : priority);
        r.setActive(active == null ? Boolean.TRUE : active);
        r.setMinWeightLb(minWeightLb);
        r.setMaxWeightLb(maxWeightLb);
        r.setDestCountries(blankToNull(destCountries));
        r.setDestRegions(blankToNull(destRegions));
        r.setMatchCarrier(blankToNull(matchCarrier));
        r.setMatchServiceId(matchServiceId);
        r.setMinDeclaredValue(minDeclaredValue);
        r.setMaxDeclaredValue(maxDeclaredValue);
        r.setMatchOrderSource(blankToNull(matchOrderSource));
        r.setMatchWarehouseId(matchWarehouseId);
        r.setActionType(actionType == null ? ActionType.REROUTE : actionType);
        r.setTargetServiceId(targetServiceId);
        r.setTargetWarehouseId(targetWarehouseId);
        r.setBlockReason(blankToNull(blockReason));
        return r;
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
