package com.multiship.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * PR-F2 - aggregate progress for one MPS ("multi-piece shipment") order
 * routed through the USPS_DIRECT persistent label queue.
 *
 * <p>Returned by
 * {@code GET /api/v1/admin/usps-direct/queue/mps-progress/{orderNo}}.
 * Operators (USER + ADMIN roles) poll this endpoint to see "order
 * 12345 is 240/1000 done, estimated finish 4:32 PM" without loading a
 * thousand queue-row DTOs.
 *
 * <p>Values are derived from a GROUP BY over
 * {@code UspsLabelQueueRepository.findStatusCountsByParentOrderNo}
 * plus a bounded fetch of the DONE rows' tracking numbers (capped at
 * {@link #TRACKING_NUMBER_PREVIEW_LIMIT} for payload size).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UspsMpsProgressDTO {

    /**
     * Cap on the {@link #trackingNumbers} preview slice. The full N-row
     * list per order could be 1000 tracking numbers; the FE only needs
     * a handful to render "recent completions" so we bound the payload.
     */
    public static final int TRACKING_NUMBER_PREVIEW_LIMIT = 20;

    /** Echo of the URL path parameter - the MPS parent order number. */
    private Long parentOrderNo;

    /**
     * Total number of pieces in the parent MPS order (sum of every
     * status bucket). Zero when no queue rows exist for this parent -
     * the endpoint returns 404 in that case, but the field defaults to
     * 0 for DTO safety.
     */
    private long totalPieces;

    /**
     * GROUP BY breakdown - the key is the {@code UspsLabelQueueItem.Status}
     * enum name ({@code QUEUED}, {@code PROCESSING}, {@code DONE},
     * {@code FAILED}, {@code CANCELLED}); the value is the count of
     * pieces in that state. Every status the parent has at least one
     * piece in appears; zero-count states are omitted (LinkedHashMap
     * insertion order gives a stable JSON shape).
     */
    private Map<String, Long> byStatus;

    /**
     * Fraction of pieces that reached {@code DONE}, as a percentage
     * (0-100 with one decimal). Callers can render "24.5% done"
     * directly; null when {@link #totalPieces} is 0.
     */
    private BigDecimal percentComplete;

    /**
     * Wall-clock estimate for when the LAST piece finishes. Computed
     * from {@code (queuedDepth / hourlyCap)} hours from the current
     * "next tick" time on a partially-drained batch; equal to the most
     * recent {@code completedAt} on a fully-drained batch.
     */
    private LocalDateTime estimatedCompletionAt;

    /**
     * Earliest {@code startedAt} across the parent's pieces - {@code null}
     * when nothing has started processing yet. Renders as "started at
     * 8:00 AM" on the admin surface.
     */
    private LocalDateTime startedAt;

    /**
     * First {@value #TRACKING_NUMBER_PREVIEW_LIMIT} completed tracking
     * numbers, in piece-sequence order. Empty list when no piece has
     * completed yet. Callers wanting the full list should paginate the
     * generic {@code /queue/items} endpoint filtered by parent - the
     * MPS-progress endpoint keeps its payload predictable + cheap.
     */
    private List<String> trackingNumbers;
}
