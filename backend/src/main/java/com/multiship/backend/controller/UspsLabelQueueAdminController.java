package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.UspsLabelQueueItemDTO;
import com.multiship.backend.dto.UspsLabelQueueMetricsDTO;
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

import java.util.List;

/**
 * USPS_DIRECT PR-F - admin surface for the persistent label queue.
 * Read-only observability + a single mutation (cancel a queued row).
 * The actual queue drain happens on a scheduler tick; this controller
 * exists to let operators see backpressure + intervene on stuck rows.
 *
 * <p>All endpoints require ADMIN. See
 * {@code docs/usps-direct-integration.md} PR-F for the queue design.
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
}
