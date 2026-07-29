package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.BulkLabelJobDTO;
import com.multiship.backend.dto.BulkLabelRequestDTO;
import com.multiship.backend.model.BulkLabelJob;

import java.util.Optional;

/**
 * Sprint 37 — bulk label generation. Submits a background job that
 * generates labels for N orders in parallel, tracks progress via a
 * polled status endpoint, and zips the resulting PDFs into a single
 * downloadable archive.
 */
public interface BulkLabelService {

    /** Submit a new bulk-label job. Returns the created job DTO. */
    ApiResponse<BulkLabelJobDTO> submit(BulkLabelRequestDTO request, String requestedBy);

    /** Fetch a job's current status for polling. */
    ApiResponse<BulkLabelJobDTO> status(Long jobId);

    /** Fetch the raw job entity — used by the download endpoint to
     *  stream the zipped PDFs. */
    Optional<BulkLabelJob> findRaw(Long jobId);
}
