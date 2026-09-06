package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Body payload for a {@code 422 SPLIT_REQUIRED} response — carries the
 * split-strategy options the operator can pick from. Returned when the
 * initial manual-shipment submit has more commodities than the carrier
 * accepts and no {@code splitStrategy} was provided in the request.
 *
 * <p>FE renders a modal with each strategy card; on pick, re-submits
 * the original request with {@code splitStrategy} populated.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SplitRequiredResponse {

    private String carrier;
    private int actualCommodityCount;
    private int carrierCap;
    private int requiredSplitCount;
    private int packageCount;
    private List<StrategyOption> strategies;

    /**
     * One strategy card in the modal. Presented to the operator with a
     * short label + operational note; the {@code code} field goes back
     * on the second submit as {@code ManualShipmentRequest.splitStrategy}.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StrategyOption {
        private String code;
        private String label;
        private int trackingCount;
        private String note;
    }
}
