package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Sprint 47 PR4 — dry-run for the split-shipment endpoint. Given the same
 * request shape as {@code /orders/multi-warehouse-label}, returns which
 * warehouse each line would ship from and how many child shipments the
 * split would generate — WITHOUT purchasing any labels.
 *
 * <p>Every line falls into one of three buckets:
 * <ul>
 *   <li><b>EXPLICIT</b> — the caller already set {@code warehouseCode} on
 *       the line; we pass it through unchanged.</li>
 *   <li><b>AUTO</b> — the line had no warehouseCode; {@link
 *       com.multiship.backend.service.warehouse.WarehouseSelector} picked
 *       one from the client's attached warehouses.</li>
 *   <li><b>NONE</b> — no warehouseCode AND the selector had nothing to
 *       pick from (client has no attached warehouses). These lines block
 *       the real write endpoint and must be resolved before submitting.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiWarehousePreviewResponse {

    private String clientCode;
    private Integer orderNo;

    /** Total lines in the input request. */
    private Integer totalLines;

    /** Number of child shipments the split would generate. Equals the
     *  number of distinct assigned warehouseCodes (excludes unassigned). */
    private Integer shipmentCount;

    /** Lines with {@code source = NONE} — the operator must fill these in
     *  before the write endpoint will accept the request. */
    private Integer unassignedLineCount;

    /** Per-warehouse rollup. Sorted first by lineCount DESC, then by
     *  warehouseCode ASC for a stable UI. Includes a {@code null}-coded
     *  bucket at the tail when there are unassigned lines. */
    private List<GroupPreview> groups;

    /** Per-line trace in input order. */
    private List<LinePreview> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupPreview {
        /** Null iff this is the unassigned bucket. */
        private String warehouseCode;
        private String warehouseName;
        private Integer lineCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinePreview {
        /** 0-based index into the request's {@code lines} array. */
        private Integer lineIndex;
        private String itemNo;
        private Integer quantity;
        /** The warehouse this line would ship from. Null when {@code source = NONE}. */
        private String assignedWarehouseCode;
        /** EXPLICIT | AUTO | NONE. */
        private String source;
        /** Selector's matchReason (COUNTRY_AND_POSTAL, COUNTRY, ANY, NONE);
         *  null for EXPLICIT (no selector call). */
        private String matchReason;
        private Long selectedWarehouseId;
        private String selectedWarehouseName;
    }
}
