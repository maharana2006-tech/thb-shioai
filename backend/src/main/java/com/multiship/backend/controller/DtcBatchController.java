package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.DtcBatchKey;
import com.multiship.backend.dto.DtcBatchStats;
import com.multiship.backend.model.DtcGenerationJob;
import com.multiship.backend.model.DtcOrder;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.DtcGenerationJobRepository;
import com.multiship.backend.repository.DtcOrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.LabelArtifactResolver;
import com.multiship.backend.service.TenantScopeEnforcer;
import com.multiship.backend.service.dtc.DtcLabelGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * DTC batch history + "Automatic label" generation (V102) — the two-level
 * flow from the shipx reference:
 *
 * <ul>
 *   <li>{@code GET /batches} — Dtcal-style summary: one row per
 *       (tenant, batch) with status/count aggregates, tenant + ship-date
 *       filters, paged.</li>
 *   <li>{@code GET /batches/{batchId}} — HstDetails-style lines, paged,
 *       each carrying its generated label's tracking/status, plus a
 *       {@code voidStatuses} map (orderNo → order_label_tracking.status)
 *       so the FE can render Void state without per-line calls.</li>
 *   <li>{@code POST /batches/{batchId}/generate} — enqueue a generation job;
 *       refused with 409 while one is active for the batch.</li>
 *   <li>{@code GET /batches/{batchId}/labels.zip} — every GENERATED row's
 *       label PDF in one archive.</li>
 * </ul>
 *
 * <p>All endpoints clamp {@code tenantId} through
 * {@link TenantScopeEnforcer}: a tenant-scoped user naming a foreign
 * tenant gets 403; an operator sees every tenant.
 */
@Tag(name = "D2C Batches", description = "DTC batch history, label generation, and batch ZIP export")
@RestController
@RequestMapping("/api/v1/dtc/batches")
@RequiredArgsConstructor
public class DtcBatchController {

    private final DtcOrderRepository dtcOrderRepository;
    private final DtcGenerationJobRepository jobRepository;
    private final OrderTrackingRepository orderTrackingRepository;
    private final DtcLabelGenerationService generationService;
    private final LabelArtifactResolver labelArtifactResolver;
    private final TenantScopeEnforcer tenantScope;

    @Operation(summary = "Batch summary (one row per tenant+batch)",
            description = "Dtcal-style aggregates. Filters: tenantId, shipDate, q (free text over batch / tote / order no / "
                    + "customer PO / ship-to name / city), labelStatus (GENERATED|PARTIAL|PENDING), batchStatus (COMPLETE|OPEN) "
                    + "and a createdFrom/createdTo range (ISO date). Tenant-scoped callers are clamped to their own tenant.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> batches(
            @RequestParam(defaultValue = "") String tenantId,
            @RequestParam(defaultValue = "") String shipDate,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String labelStatus,
            @RequestParam(defaultValue = "") String batchStatus,
            @RequestParam(defaultValue = "") String createdFrom,
            @RequestParam(defaultValue = "") String createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        String tenantFilter = Optional.ofNullable(tenantScope.clampClientCode(nullIfBlank(tenantId)))
                .orElse("");
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        Page<DtcBatchKey> keys = dtcOrderRepository.findBatchKeys(
                tenantFilter,
                trimOrEmpty(shipDate),
                trimOrEmpty(q),
                oneOf(labelStatus, LABEL_STATUSES, "labelStatus"),
                oneOf(batchStatus, BATCH_STATUSES, "batchStatus"),
                startOfDay(createdFrom, UNBOUNDED_FROM),
                endOfDay(createdTo, UNBOUNDED_TO),
                pageable);

        List<DtcBatchStats> content = keys.getContent().stream()
                .map(k -> dtcOrderRepository.summarizeBatch(k.tenantId(), k.batchId()).orElse(null))
                .filter(Objects::nonNull)
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", content);
        data.put("pageNumber", keys.getNumber());
        data.put("pageSize", keys.getSize());
        data.put("totalElements", keys.getTotalElements());
        data.put("totalPages", keys.getTotalPages());
        return ok("DTC batches", data);
    }

    @Operation(summary = "Batch lines (HstDetails page)",
            description = "Paged dtc_orders rows for one tenant+batch, each realigned with its label order first, "
                    + "plus voidStatuses (orderNo → tracking status) for the lines that have a generated order.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/{batchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchDetail(
            @PathVariable BigDecimal batchId,
            @RequestParam(defaultValue = "") String tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        String tenant = requireTenant(tenantId);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500),
                Sort.by(Sort.Direction.ASC, "id"));
        Page<DtcOrder> lines = dtcOrderRepository.findByTenantIdAndBatchId(tenant, batchId, pageable);

        // A line repaired on the shipment form is written by that path, not by the DTC
        // worker, so re-read its label order here: the page then shows what was actually
        // bought. Writes only the lines that drifted, and writes nothing for the rows
        // that never had a label order.
        lines.getContent().forEach(generationService::syncFromLabel);

        Map<Integer, String> voidStatuses = voidStatusesFor(lines.getContent());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", lines.getContent());
        data.put("voidStatuses", voidStatuses);
        data.put("pageNumber", lines.getNumber());
        data.put("pageSize", lines.getSize());
        data.put("totalElements", lines.getTotalElements());
        data.put("totalPages", lines.getTotalPages());
        dtcOrderRepository.summarizeBatch(tenant, batchId)
                .ifPresent(stats -> data.put("batch", stats));
        return ok("DTC batch " + batchId, data);
    }

    @Operation(summary = "Enqueue batch label generation",
            description = "\"Automatic label\" — mints/regenerates a label order per row. 409 while a job is already active for the batch.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/{batchId}/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generate(
            @PathVariable BigDecimal batchId,
            @RequestParam(defaultValue = "") String tenantId) {

        String tenant = requireTenant(tenantId);
        Optional<DtcGenerationJob> queued =
                generationService.enqueue(tenant, batchId, currentUsername());

        if (queued.isEmpty()) {
            DtcGenerationJob active = jobRepository
                    .findFirstByTenantIdAndBatchIdOrderByQueuedAtDesc(tenant, batchId)
                    .orElse(null);
            Map<String, Object> data = new LinkedHashMap<>();
            if (active != null) {
                data.put("job", active);
            }
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.<Map<String, Object>>builder()
                            .status("ERROR").code(409)
                            .timestamp(LocalDateTime.now())
                            .message("A generation job is already active for tenant " + tenant
                                    + ", batch " + batchId)
                            .data(data).build());
        }
        DtcGenerationJob job = queued.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("job", job);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.<Map<String, Object>>builder()
                        .status("SUCCESS").code(202)
                        .timestamp(LocalDateTime.now())
                        .message("Generation job queued")
                        .data(data).build());
    }

    @Operation(summary = "Generation job status", description = "Progress poll target after enqueue.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/generation-jobs/{jobId}")
    public ResponseEntity<ApiResponse<DtcGenerationJob>> jobStatus(@PathVariable Long jobId) {
        DtcGenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("job " + jobId + " not found"));
        tenantScope.requireTenantMatch(job.getTenantId());
        return ok("DTC generation job", job);
    }

    @Operation(summary = "Batch label ZIP",
            description = "Every GENERATED row's label PDF in one archive. Rows still queued on the USPS worker have no PDF yet and are skipped.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping(value = "/{batchId}/labels.zip", produces = "application/zip")
    public ResponseEntity<?> batchLabelsZip(
            @PathVariable BigDecimal batchId,
            @RequestParam(defaultValue = "") String tenantId) throws Exception {

        String tenant = requireTenant(tenantId);
        List<DtcOrder> rows = dtcOrderRepository.findByTenantIdAndBatchIdOrderByIdAsc(tenant, batchId)
                .stream()
                .filter(r -> "GENERATED".equalsIgnoreCase(
                        r.getGeneratedStatus() == null ? "" : r.getGeneratedStatus()))
                .filter(r -> r.getGeneratedOrderNo() != null)
                .toList();

        ByteArrayOutputStream zipBytes;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bos)) {
            int added = 0;
            for (DtcOrder row : rows) {
                Optional<byte[]> pdf =
                        labelArtifactResolver.resolveAsBytes(row.getGeneratedOrderNo(), "PDF", null);
                if (pdf.isEmpty()) continue;
                zip.putNextEntry(new ZipEntry("label-" + row.getGeneratedOrderNo() + ".pdf"));
                zip.write(pdf.get());
                zip.closeEntry();
                added++;
            }
            zip.finish();
            if (added == 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.<Void>builder()
                                .status("ERROR").code(404)
                                .timestamp(LocalDateTime.now())
                                .message("No label PDFs ready for tenant " + tenant + ", batch " + batchId
                                        + (rows.isEmpty()
                                                ? " (generate labels first)"
                                                : " (labels are still pending on the label queue)"))
                                .build());
            }
            zipBytes = bos;
        }

        String filename = "dtc-batch-" + batchId + "-labels.zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(zipBytes.toByteArray());
    }

    @Operation(summary = "Distinct ship dates", description = "Date-filter options for the summary page.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/ship-dates")
    public ResponseEntity<ApiResponse<List<String>>> shipDates() {
        return ok("DTC ship dates", dtcOrderRepository.findDistinctShipDates());
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Effective tenant for a batch-scoped endpoint: clamp the caller's pick
     * through the scope policy, then require a value — a batch id is only
     * meaningful alongside its tenant (the same batch number can recur
     * across tenants in the composite key).
     */
    private String requireTenant(String requested) {
        String tenant = tenantScope.clampClientCode(nullIfBlank(requested));
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return tenant.trim();
    }

    private Map<Integer, String> voidStatusesFor(List<DtcOrder> lines) {
        List<Integer> orderNos = lines.stream()
                .map(DtcOrder::getGeneratedOrderNo)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (orderNos.isEmpty()) {
            return Map.of();
        }
        return orderTrackingRepository.findByOrderNoIn(orderNos).stream()
                .filter(t -> t.getOrderNo() != null && t.getStatus() != null)
                .collect(Collectors.toMap(OrderTracking::getOrderNo, OrderTracking::getStatus,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private static String currentUsername() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .map(Authentication::getName)
                .orElse(null);
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Widest bounds the summary query uses when a date input is left open. */
    private static final LocalDateTime UNBOUNDED_FROM = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime UNBOUNDED_TO = LocalDateTime.of(2999, 12, 31, 23, 59, 59);
    private static final List<String> LABEL_STATUSES = List.of("GENERATED", "PARTIAL", "PENDING");
    private static final List<String> BATCH_STATUSES = List.of("COMPLETE", "OPEN");

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** Whitelist an enum-like filter: a typo should be a 400, not a silently empty list. */
    private static String oneOf(String value, List<String> allowed, String param) {
        String trimmed = trimOrEmpty(value);
        if (trimmed.isEmpty()) {
            return "";
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (!allowed.contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, param + " must be one of " + allowed);
        }
        return upper;
    }

    private static LocalDateTime startOfDay(String isoDate, LocalDateTime fallback) {
        LocalDate parsed = parseIsoDate(isoDate);
        return parsed == null ? fallback : parsed.atStartOfDay();
    }

    private static LocalDateTime endOfDay(String isoDate, LocalDateTime fallback) {
        LocalDate parsed = parseIsoDate(isoDate);
        return parsed == null ? fallback : parsed.atTime(LocalTime.MAX);
    }

    private static LocalDate parseIsoDate(String isoDate) {
        String trimmed = trimOrEmpty(isoDate);
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "date filters must be YYYY-MM-DD, got: " + trimmed);
        }
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("SUCCESS").code(200)
                .timestamp(LocalDateTime.now())
                .message(message).data(data).build());
    }
}
