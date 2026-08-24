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
