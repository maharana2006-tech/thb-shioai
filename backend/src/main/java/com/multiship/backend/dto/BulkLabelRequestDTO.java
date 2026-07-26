package com.multiship.backend.dto;

import jakarta.validation.constraints.NotEmpty;
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

    @NotEmpty
    private List<Long> orderNumbers;
}
