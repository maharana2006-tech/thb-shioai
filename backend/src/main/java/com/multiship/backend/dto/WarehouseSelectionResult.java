package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * G3 — outcome of {@code WarehouseSelector.selectNearest}.
 *
 * <p>{@link #selectedWarehouseId} is null when the client has no attached
 * warehouses (matchReason = NONE). {@link #candidates} always contains
 * every attached warehouse in the order they were scored — for a dry-run
 * UI to display the tie-breaker trace.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseSelectionResult {

    /** COUNTRY_AND_POSTAL | COUNTRY | ANY | NONE. */
    private String matchReason;

    private Long selectedWarehouseId;
    private String selectedWarehouseCode;
    private String selectedWarehouseName;

    /** Number of leading postal chars that matched. 0 when the winner was
     *  picked without a postal match. */
    private Integer postalPrefixLength;

    private List<Candidate> candidates;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Candidate {
        private Long warehouseId;
        private String warehouseCode;
        private String warehouseName;
        /** Country on the warehouse's address, or null. */
        private String warehouseCountry;
        /** Postal on the warehouse's address, or null. */
        private String warehousePostal;
        private Boolean isDefault;
        private Integer score;
        private Boolean sameCountry;
        /** Length of common leading postal chars with the destination. */
        private Integer postalPrefixLength;
        /** One-line "why this rank". */
        private String reason;
    }
}
