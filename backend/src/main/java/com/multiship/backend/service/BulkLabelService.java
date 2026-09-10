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

    /**
     * Cooperatively cancel a running job. Marks the job as CANCELLED
     * and sets an in-memory flag that workers check between orders;
     * already-in-flight carrier calls run to completion (we can't
     * interrupt a paid label mid-request without leaking it). Returns
     * 404 if the job is unknown, 409 if the job is already in a
     * terminal state (COMPLETED/FAILED/CANCELLED).
     */
    ApiResponse<BulkLabelJobDTO> cancel(Long jobId);
}
