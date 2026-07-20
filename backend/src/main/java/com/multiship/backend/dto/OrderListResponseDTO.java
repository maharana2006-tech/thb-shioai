package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderListResponseDTO {
    private Long totalRecords;
    private List<OrderResponseDTO> orders;
    private Summary summary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private BigDecimal totalWeight;
        private BigDecimal averageWeight;
        private Long pendingLabels;
        private Long generatedLabels;
        private Long failedLabels;
        private List<String> cities;
        private Map<String, Long> statusCounts;
    }
}