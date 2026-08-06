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
    private int totalRows;
    private int savedRows;
    private int invalidRows;
    private List<OrderImportRowDTO> rows;
}
