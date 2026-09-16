package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.UspsLabelQueueItemDTO;
import com.multiship.backend.dto.UspsLabelQueueMetricsDTO;
import com.multiship.backend.dto.UspsMpsProgressDTO;
import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.model.UspsLabelQueueItem.Status;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * USPS_DIRECT PR-F - admin surface for the persistent label queue.
 * Read-only observability + a single mutation (cancel a queued row).
 * The actual queue drain happens on a scheduler tick; this controller
 * exists to let operators see backpressure + intervene on stuck rows.
 *
 * <p>Most endpoints require ADMIN. PR-F2 adds {@code /mps-progress/{orderNo}}
 * which relaxes to {@code hasRole('ADMIN') or hasRole('USER')} because
 * operators (USER role) need it to check on their own MPS orders, not
 * just admin.
 *
 * <p>See {@code docs/usps-direct-integration.md} PR-F for the queue design.
 */
@Tag(name = "USPS Direct label queue (admin)",
        description = "USPS_DIRECT PR-F - admin surface for the platform-wide USPS label queue")
@RestController
@RequestMapping("/api/v1/admin/usps-direct/queue")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UspsLabelQueueAdminController {

    private final UspsLabelQueueService service;
    private final UspsLabelQueueRepository repository;

    /** Cap on the /items page size so an accidental huge page can't
     *  spike the DB. */
    private static final int MAX_PAGE_SIZE = 200;

    @Operation(summary = "Backpressure metrics for the USPS label queue",
            description = "Platform-wide by default. Pass ?tenant=CODE for a tenant-scoped shape.")
    @GetMapping("/metrics")
    public ResponseEntity<ApiResponse<UspsLabelQueueMetricsDTO>> metrics(
            @RequestParam(name = "tenant", required = false) String tenant) {
        UspsLabelQueueMetricsDTO data = (tenant == null || tenant.isBlank())
                ? service.getMetrics()
                : service.getMetricsForTenant(tenant);
        return ok(data);
    }

    @Operation(summary = "Paginated list of queue rows",
            description = "Ordered by enqueued_at DESC so the newest work is on top.")
    @GetMapping("/items")
    public ResponseEntity<ApiResponse<List<UspsLabelQueueItemDTO>>> items(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        Page<UspsLabelQueueItemDTO> pageData = repository
                .findAllByOrderByEnqueuedAtDesc(PageRequest.of(safePage, safeSize))
                .map(UspsLabelQueueItemDTO::from);
        return ok(pageData.getContent());
    }

    @Operation(summary = "Cancel a queued item",
            description = "204 on success (QUEUED -> CANCELLED). 409 when the row is already PROCESSING/DONE/FAILED. 404 when the id doesn't exist.")
    @DeleteMapping("/items/{id}")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable Long id) {
        // Peek the row so we can distinguish 404 (missing) from 409
        // (present but not cancellable). service.cancel() is idempotent
        // and returns false for both cases, which the admin surface
        // needs to disambiguate for the operator's UX.
        boolean exists = repository.findById(id).isPresent();
        if (!exists) return notFound();

        boolean cancelled = service.cancel(id);
        if (cancelled) return noContent();
        return conflict();
    }

    // ============================================================
    // PR-F2 - MPS aggregate progress
    // ============================================================

    /**
     * PR-F2 - aggregate progress for one MPS parent order.
     *
     * <p>Operators poll this to see "order 12345 is 240/1000 done" without
     * pulling every piece row. Response shape lives in
     * {@link UspsMpsProgressDTO}; 404 when the order has no MPS queue
     * rows (single-label orders don't count).
     *
     * <p>Relaxed to {@code USER} in addition to {@code ADMIN} — the class
     * default is ADMIN-only, but MPS progress is per-order observability
     * that operators need on their own shipments. The lookup is
     * order-scoped (not tenant-scoped) so cross-tenant guard is not
     * imposed here; tenant enforcement happens upstream at the order-
     * loading path when an operator navigates to their own order's
     * status page.
     */
    @Operation(summary = "Aggregate progress for one MPS parent order",
            description = "GROUP BY status over the parent's queue rows + a "
                    + "first-N tracking-number preview. 404 when the order has no MPS queue rows.")
    @PreAuthorize("hasRole('ADMIN') or hasRole('USER')")
    @GetMapping("/mps-progress/{orderNo}")
    public ResponseEntity<ApiResponse<UspsMpsProgressDTO>> mpsProgress(@PathVariable Long orderNo) {
        if (orderNo == null) {
            return mpsProgressNotFound(orderNo);
        }
        List<UspsLabelQueueRepository.StatusCount> statusCounts =
                repository.findStatusCountsByParentOrderNo(orderNo);
        long totalPieces = statusCounts.stream().mapToLong(UspsLabelQueueRepository.StatusCount::count).sum();
        if (totalPieces <= 0) {
            return mpsProgressNotFound(orderNo);
        }

        // GROUP BY breakdown -> LinkedHashMap so JSON key order is stable
        // (matches the enum declaration order for readability).
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long doneCount = 0L;
        for (Status s : Status.values()) {
            for (UspsLabelQueueRepository.StatusCount sc : statusCounts) {
                if (sc.status() == s) {
                    byStatus.put(s.name(), sc.count());
                    if (s == Status.DONE) doneCount = sc.count();
                    break;
                }
            }
        }

        // Load the parent's piece rows for tracking-number preview +
        // startedAt / lastCompletedAt derivation. Bounded by MPS max size
        // (~1000 pieces per order); no pagination.
        List<UspsLabelQueueItem> pieces = repository.findByParentOrderNoOrderBySequenceNumberAsc(orderNo);

        List<String> trackingPreview = new java.util.ArrayList<>();
        LocalDateTime earliestStarted = null;
        LocalDateTime latestCompleted = null;
        for (UspsLabelQueueItem row : pieces) {
            if (row.getStartedAt() != null
                    && (earliestStarted == null || row.getStartedAt().isBefore(earliestStarted))) {
                earliestStarted = row.getStartedAt();
            }
            if (row.getStatus() == Status.DONE
                    && row.getCompletedAt() != null
                    && (latestCompleted == null || row.getCompletedAt().isAfter(latestCompleted))) {
                latestCompleted = row.getCompletedAt();
            }
            if (row.getStatus() == Status.DONE
                    && row.getTrackingNumber() != null
                    && trackingPreview.size() < UspsMpsProgressDTO.TRACKING_NUMBER_PREVIEW_LIMIT) {
                trackingPreview.add(row.getTrackingNumber());
            }
        }

        // percentComplete: DONE / total * 100, one decimal. Uses BigDecimal
        // so the FE can trust the wire value (Jackson-serialised as a
        // numeric literal) without JS float rounding drift.
        BigDecimal percent = totalPieces == 0
                ? null
                : BigDecimal.valueOf(doneCount)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalPieces), 1, RoundingMode.HALF_UP);

        // estimatedCompletionAt:
        //   - Fully drained: latestCompleted (real wall-clock).
        //   - Partially drained: derive from the platform metrics'
        //     estimatedStartAt (which already reflects backpressure) +
        //     the remaining-pieces / hourlyCap in seconds.
        LocalDateTime estCompletion;
        long remainingPieces = totalPieces - doneCount
                - byStatus.getOrDefault(Status.FAILED.name(), 0L)
                - byStatus.getOrDefault(Status.CANCELLED.name(), 0L);
        if (remainingPieces <= 0) {
            estCompletion = latestCompleted;
        } else {
            // Use the platform metrics' cap to project. Cheap - one query.
            UspsLabelQueueMetricsDTO m = service.getMetrics();
            long hourlyCap = m.getConfiguredHourlyCap() <= 0 ? 55L : m.getConfiguredHourlyCap();
            long secondsRemaining = (long) Math.ceil((double) remainingPieces / (double) hourlyCap * 3600.0);
            LocalDateTime base = m.getEstimatedStartAt() != null
                    ? m.getEstimatedStartAt() : LocalDateTime.now();
            estCompletion = base.plusSeconds(secondsRemaining);
        }

        UspsMpsProgressDTO dto = UspsMpsProgressDTO.builder()
                .parentOrderNo(orderNo)
                .totalPieces(totalPieces)
                .byStatus(byStatus)
                .percentComplete(percent)
                .estimatedCompletionAt(estCompletion)
                .startedAt(earliestStarted)
                .trackingNumbers(trackingPreview)
                .build();
        return ok(dto);
    }

    // ============================================================
    // ApiResponse envelope helpers - mirror UspsDirectSubscriptionAdminController.
    // ============================================================

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("success").code(HttpStatus.OK.value()).data(data).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> noContent() {
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(ApiResponse.<T>builder()
                .status("success").code(HttpStatus.NO_CONTENT.value()).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.NOT_FOUND.value())
                .message("queue item not found")
                .errorCode(ErrorCode.VALIDATION_ERROR.name()).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.CONFLICT.value())
                .message("queue item is not cancellable in its current state (PROCESSING/DONE/FAILED/CANCELLED)")
                .errorCode(ErrorCode.VALIDATION_ERROR.name()).build());
    }

    /** PR-F2 - dedicated 404 wording so operators know the order has no
     *  MPS pieces (not just "no queue rows at all"). */
    private static ResponseEntity<ApiResponse<UspsMpsProgressDTO>> mpsProgressNotFound(Long orderNo) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.<UspsMpsProgressDTO>builder()
                .status("error").code(HttpStatus.NOT_FOUND.value())
                .message("order " + orderNo + " has no MPS queue rows (either not MPS or never routed to USPS Direct)")
                .errorCode(ErrorCode.VALIDATION_ERROR.name()).build());
    }
}
