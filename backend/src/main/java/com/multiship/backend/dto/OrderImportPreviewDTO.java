package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Sprint 40 response DTO for the preview endpoint. Carries the parsed
 * rows plus summary counts so the operator sees at a glance how many
 * of N rows have errors.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderImportPreviewDTO {

    private int totalRows;
    private int validRows;
    private int invalidRows;
    /** Id shared by every order generated from this commit's file upload. Null on preview. */
    private Integer batchId;
    private List<OrderImportRowDTO> rows;
}
