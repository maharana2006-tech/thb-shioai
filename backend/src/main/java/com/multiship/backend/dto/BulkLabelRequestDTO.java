package com.multiship.backend.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Sprint 37 request body for {@code POST /api/v1/bulk-labels}. Kicks
 * off a background job that generates labels for every order in
 * {@code orderNumbers} and zips the resulting PDFs into a single
 * downloadable archive.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkLabelRequestDTO {

    // Sprint 52 — cap the batch size at 500 orders. Streaming-download
    // and memory pressure scale with batch size; >500 in a single request
    // pushed the worker executor into pool exhaustion. Operators split
    // larger batches into multiple submissions.
    @NotEmpty
    @Size(max = 500, message = "Bulk batch limited to 500 orders — split larger batches into multiple submissions")
    private List<Long> orderNumbers;
}
