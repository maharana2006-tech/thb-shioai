package com.multiship.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Sprint 37 request body for {@code POST /api/v1/bulk-labels}. Kicks
 * off a background job that generates labels for every order in the
 * request and zips the resulting PDFs into a single downloadable archive.
 *
 * <p>Two lookup modes are supported; the caller picks exactly one per
 * request (the service returns 400 if both are set or both are empty):
 *
 * <ul>
 *   <li>{@link #orderNumbers} — the original Sprint-37 shape. Numeric
 *       internal {@link com.multiship.backend.model.Order#getOrderNo} list.
 *       Kept for back-compat; all existing callers continue to work.</li>
 *   <li>{@link #identifiers} — F1 addition. Polymorphic entries of
 *       {@code {type, value}} that mix orderNo + orderRef lookups in one
 *       job. {@code orderRef} entries resolve against
 *       {@link com.multiship.backend.model.Order#getWmsExternalId}
 *       case-insensitively; unresolvable entries fail the whole submission
 *       with an itemised 400 (fail-fast, matches the pre-F1 tenant-guard
 *       behavior).</li>
 * </ul>
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
    //
    // F1 — no longer @NotEmpty; either this field OR identifiers must be
    // populated. Service-level XOR check surfaces the actionable
    // ErrorCode.VALIDATION_ERROR when the caller sends neither or both.
    @Size(max = 500, message = "Bulk batch limited to 500 orders — split larger batches into multiple submissions")
    private List<Long> orderNumbers;

    /**
     * F1 — polymorphic identifier list. Each entry names both the lookup
     * mode ({@code orderNo} or {@code orderRef}) and the value to resolve.
     * Mix-mode allowed: a single request may list orderNo + orderRef
     * entries interleaved; the service resolves each independently and
     * enqueues the union.
     *
     * <p>Same 500-item cap as {@link #orderNumbers}. Nested Bean Validation
     * runs on each entry via {@link Valid}.
     */
    @Valid
    @Size(max = 500, message = "Bulk batch limited to 500 identifiers — split larger batches into multiple submissions")
    private List<BulkLabelIdentifierDTO> identifiers;
}
