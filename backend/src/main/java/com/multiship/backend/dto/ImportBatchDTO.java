package com.multiship.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** A saved import for the Data History page. `rows` is null in the list, populated in the detail. */
@Data
@Builder
public class ImportBatchDTO {
    private Long id;
    private String createdBy;
    private String createdAt;
    /**
     * ISO timestamp of the last terminal transition
     * (COMPLETE / PARTIAL_COMPLETE / FAILED / CANCELLED). Null when the
     * batch hasn't finished a generation run yet. Updated on retries.
     */
    private String completedAt;
    /**
     * Optional batch-level note (Batch #11 post-mortem, 2026-09-12) —
     * rendered under the status pill in the FE. Populated when a
     * generate run exhausts its retry passes with rows still rate-
     * limited. Cleared when the operator retries. Max 500 chars.
     */
    private String note;
    /** Original uploaded file name the rows came from. */
    private String fileName;
    /** INITIATE | IN_PROGRESS | PARTIAL_COMPLETE | COMPLETE. */
    private String status;
    /** Label batch id shared by every order this import generated. Null until first generation. */
    private Integer labelBatchId;
    private int totalRows;
    private int savedRows;
    private int invalidRows;
    /** Soft-delete timestamp (ISO string). Null = live; non-null = in Trash. */
    private String deletedAt;
    /** User who moved this batch to Trash (null while live). */
    private String deletedBy;
    /** Bill-to account mode: AUTO (cascade) or PLATFORM (house account). */
    private String billingMode;
    /** Origin of the rows: BULK (uploaded file) or WMS (Fetch from WMS). Null = BULK. */
    private String source;
    private List<OrderImportRowDTO> rows;
}
