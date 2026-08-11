package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.BulkLabelJobDTO;
import com.multiship.backend.dto.BulkLabelRequestDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.model.BulkLabelJob;
import com.multiship.backend.repository.BulkLabelJobRepository;
import com.multiship.backend.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Sprint 37 impl. Backpressure via a bounded thread pool so a 500-order
 * submission doesn't OOM. Every per-order label call reuses the
 * existing single-label pipeline ({@link CarrierService#generateLabel})
 * so bulk shares the same idempotency + credential resolution as a
 * one-off click.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkLabelServiceImpl implements BulkLabelService {

    /** Max concurrent per-order labels — carriers rate-limit at ~5-10 rps
     *  per account. Keeping it conservative so a big job doesn't trip
     *  carrier throttling. */
    private static final int WORKER_CONCURRENCY = 4;

    /** HTTP timeout for downloading a label PDF from the carrier's CDN. */
    private static final Duration LABEL_FETCH_TIMEOUT = Duration.ofSeconds(15);

    private final BulkLabelJobRepository jobRepository;
    private final CarrierService carrierService;
    /**
     * Sprint 50 Tier 0.5 PR E - guard so a scoped USER cannot enqueue a
     * bulk job containing an order from a foreign tenant. We check every
     * order in the request at submit time; any foreign hit rejects the
     * whole batch (fail-fast, per the existing invariant).
     */
    private final OrderRepository orderRepository;
    private final TenantScopeEnforcer tenantScope;

    /** Fan-out executor for the per-order workers. Shared across every
     *  job because job kick-off already runs on its own thread. */
    private final ExecutorService fanOutExecutor = Executors.newFixedThreadPool(
            WORKER_CONCURRENCY, r -> {
                Thread t = new Thread(r, "bulk-label-worker");
                t.setDaemon(true);
                return t;
            });

    /** Dispatch executor that lifts the job kick-off off the calling
     *  HTTP thread — otherwise the POST /bulk-labels response wouldn't
     *  return until the whole batch was done. */
    private final ExecutorService dispatchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bulk-label-dispatcher");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(LABEL_FETCH_TIMEOUT)
            .build();

    @Override
    public ApiResponse<BulkLabelJobDTO> submit(BulkLabelRequestDTO request, String requestedBy) {
        if (request == null || request.getOrderNumbers() == null || request.getOrderNumbers().isEmpty()) {
            return failure(HttpStatus.BAD_REQUEST, "orderNumbers is required.");
        }
        // Sprint 50 Tier 0.5 PR E - tenant guard. Verify EVERY order in
        // the request belongs to the caller's tenant before enqueuing;
        // any foreign order aborts the whole submission (fail-fast).
        // Platform operators pass through unchanged.
        for (Long orderNo : request.getOrderNumbers()) {
            if (orderNo == null) continue;
            orderRepository.findByOrderNo(orderNo.intValue()).ifPresent(o ->
                    tenantScope.requireTenantMatch(
                            StringUtils.hasText(o.getTenantId()) ? o.getTenantId() : o.getCustNo()));
        }
        // Persist the job row before we return so the client can poll
        // immediately.
        BulkLabelJob job = new BulkLabelJob();
        job.setOrderNumbers(request.getOrderNumbers().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")));
        job.setRequestedBy(requestedBy);
        job.setStatus("PENDING");
        job.setTotalCount(request.getOrderNumbers().size());
        job.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        BulkLabelJob saved = jobRepository.save(job);

        // Kick off the worker; return before it starts touching carriers.
        dispatchExecutor.submit(() -> runJob(saved.getId()));

        return success(toDto(saved), "Bulk-label job submitted.");
    }

    /** Sprint 49 Tier 3 Fix 4 — throttle progress persistence. */
    private static final int PROGRESS_FLUSH_EVERY_N = 25;
    private static final long PROGRESS_FLUSH_EVERY_MS = 2_000;

    /**
     * Per-order outcome so parallel workers can build up results without
     * mutating shared state on every step.
     */
    private record OrderOutcome(long orderNo, byte[] pdf, String trackingNumber, String failureReason) {}

    /**
     * Job worker. Loads the freshly-saved job, submits each order to the
     * fan-out pool ({@link #WORKER_CONCURRENCY} labels in flight at once),
     * assembles the results in input order into a ZIP, and persists a
     * running progress count throttled to every {@value #PROGRESS_FLUSH_EVERY_N}
     * orders or {@value #PROGRESS_FLUSH_EVERY_MS} ms.
     *
     * <p>Sprint 49 Tier 3 Fix 4 — was serial (one label at a time,
     * ignoring fanOutExecutor) with a save() per order. A 500-order job
     * used to do 500 UPDATEs on the job row and take 4x longer than
     * needed; now WORKER_CONCURRENCY parallel workers with ~20 progress
     * UPDATEs total.
     */
    void runJob(Long jobId) {
        BulkLabelJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Bulk-label job {} disappeared before the worker started", jobId);
            return;
        }
        job.setStatus("RUNNING");
        job.setStartedAt(LocalDateTime.now(ZoneOffset.UTC));
        jobRepository.save(job);

        long[] orderNos = parseOrderNumbers(job.getOrderNumbers());
        StringBuilder failures = new StringBuilder();

        // Progress tracking mutated from callback + timer.
        java.util.concurrent.atomic.AtomicLong lastFlushMs =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        java.util.concurrent.atomic.AtomicInteger doneSinceLastFlush =
                new java.util.concurrent.atomic.AtomicInteger();

        try (ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(zipBytes)) {

            // Submit every order to the fan-out pool. The pool size (4)
            // bounds concurrency naturally; extra tasks queue.
            java.util.List<java.util.concurrent.Callable<OrderOutcome>> tasks = new java.util.ArrayList<>(orderNos.length);
            for (long orderNo : orderNos) {
                tasks.add(() -> processOneOrder(orderNo));
            }
            java.util.List<java.util.concurrent.Future<OrderOutcome>> futures = fanOutExecutor.invokeAll(tasks);

            // Aggregate in input order so the ZIP is stable + failure messages
            // read in the order the caller submitted.
            for (java.util.concurrent.Future<OrderOutcome> f : futures) {
                OrderOutcome out;
                try {
                    out = f.get();
                } catch (Exception e) {
                    // Worker itself threw — shouldn't happen since processOneOrder
                    // catches internally, but guard for safety.
                    log.warn("Bulk-label worker future.get() threw: {}", e.getMessage());
                    job.setFailedCount(job.getFailedCount() + 1);
                    failures.append("worker failure: ").append(e.getMessage()).append('\n');
                    continue;
                }

                if (out.pdf != null && out.trackingNumber != null) {
                    String entryName = "label-" + out.orderNo + "-" + out.trackingNumber + ".pdf";
                    zip.putNextEntry(new ZipEntry(entryName));
                    zip.write(out.pdf);
                    zip.closeEntry();
                    job.setSuccessfulCount(job.getSuccessfulCount() + 1);
                } else {
                    job.setFailedCount(job.getFailedCount() + 1);
                    if (out.failureReason != null) {
                        failures.append("order ").append(out.orderNo)
                                .append(": ").append(out.failureReason).append('\n');
                    }
                }

                // Throttled progress flush — every N orders or every M ms.
                int done = doneSinceLastFlush.incrementAndGet();
                long now = System.currentTimeMillis();
                if (done >= PROGRESS_FLUSH_EVERY_N
                        || (now - lastFlushMs.get()) >= PROGRESS_FLUSH_EVERY_MS) {
                    jobRepository.save(job);
                    doneSinceLastFlush.set(0);
                    lastFlushMs.set(now);
                }
            }

            zip.finish();
            byte[] all = zipBytes.toByteArray();
            if (all.length > 0 && job.getSuccessfulCount() > 0) {
                job.setResultZipBase64(Base64.getEncoder().encodeToString(all));
            }
            job.setStatus("COMPLETED");
        } catch (Exception ex) {
            log.warn("Bulk-label job {} failed globally: {}", jobId, ex.getMessage());
            job.setStatus("FAILED");
            failures.append("global: ").append(ex.getMessage()).append('\n');
        } finally {
            if (failures.length() > 0) job.setFailureMessage(failures.toString());
            job.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC));
            jobRepository.save(job);
        }
    }

    /**
     * Worker body — runs on a fan-out pool thread. Never throws;
     * returns a marker OrderOutcome the caller aggregates.
     */
    private OrderOutcome processOneOrder(long orderNo) {
        try {
            LabelGenerationResponse label = generateSingle(orderNo);
            if (label == null) {
                return new OrderOutcome(orderNo, null, null, "label service returned null");
            }
            if (!StringUtils.hasText(label.getTrackingNumber())) {
                return new OrderOutcome(orderNo, null, null,
                        label.getMessage() == null ? "unknown" : label.getMessage());
            }
            byte[] pdf = downloadLabelPdf(label);
            if (pdf == null) {
                return new OrderOutcome(orderNo, null, null, "label URL unreachable");
            }
            return new OrderOutcome(orderNo, pdf, label.getTrackingNumber(), null);
        } catch (Exception ex) {
            log.warn("Bulk-label worker: order {} failed: {}", orderNo, ex.getMessage());
            return new OrderOutcome(orderNo, null, null,
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    /**
     * Delegate to the existing single-order label pipeline. Idempotency
     * key + accountId are null — bulk operators aren't picking accounts
     * per row; they want the default cascade to resolve each order's
     * carrier account.
     */
    LabelGenerationResponse generateSingle(long orderNo) {
        ApiResponse<LabelGenerationResponse> resp = carrierService
                .generateLabel(orderNo, null, "bulk-" + orderNo, null);
        return resp == null ? null : resp.getData();
    }

    /**
     * Download the label PDF bytes. Prefers {@code labelPdf} (already
     * base64) when the carrier returned one inline; falls back to
     * fetching {@code labelUrl} over HTTP.
     */
    byte[] downloadLabelPdf(LabelGenerationResponse label) {
        if (StringUtils.hasText(label.getLabelPdf())) {
            String base64 = label.getLabelPdf().trim();
            // Some carriers return "data:application/pdf;base64,..." — strip prefix.
            int comma = base64.indexOf(',');
            if (base64.startsWith("data:") && comma > 0) {
                base64 = base64.substring(comma + 1);
            }
            try {
                return Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException ignored) {
                // Not base64 — fall through to URL fetch.
            }
        }
        if (!StringUtils.hasText(label.getLabelUrl())) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(label.getLabelUrl()))
                    .timeout(LABEL_FETCH_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) return null;
            return response.body();
        } catch (Exception ex) {
            log.warn("Bulk-label PDF fetch failed for {}: {}",
                    label.getLabelUrl(), ex.getMessage());
            return null;
        }
    }

    private static long[] parseOrderNumbers(String csv) {
        if (csv == null || csv.isBlank()) return new long[0];
        String[] parts = csv.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Long.parseLong(parts[i].trim()); }
            catch (NumberFormatException ignored) { out[i] = 0; }
        }
        return out;
    }

    @Override
    public ApiResponse<BulkLabelJobDTO> status(Long jobId) {
        Optional<BulkLabelJob> job = jobRepository.findById(jobId);
        if (job.isEmpty()) {
            return failure(HttpStatus.NOT_FOUND, "Bulk-label job " + jobId + " not found.");
        }
        // Sprint 50 Tier 0.5 PR G — belt guard on jobId enumeration.
        // The submit path clamps every order in the job, so all orders
        // belong to a single tenant. Load the first order and match its
        // tenant against the caller's scope.
        requireJobTenantMatch(job.get());
        return success(toDto(job.get()), job.get().getStatus());
    }

    @Override
    public Optional<BulkLabelJob> findRaw(Long jobId) {
        Optional<BulkLabelJob> job = jobRepository.findById(jobId);
        job.ifPresent(this::requireJobTenantMatch);
        return job;
    }

    /** Sprint 50 Tier 0.5 PR G — cheapest possible loaded-row guard: the
     *  submit path clamps every order, so the whole job is tenant-uniform.
     *  Pull the first orderNumber, load the order, requireTenantMatch. */
    private void requireJobTenantMatch(BulkLabelJob job) {
        long[] orderNos = parseOrderNumbers(job.getOrderNumbers());
        if (orderNos.length == 0) return;
        orderRepository.findByOrderNo((int) orderNos[0]).ifPresent(o ->
                tenantScope.requireTenantMatch(
                        StringUtils.hasText(o.getTenantId()) ? o.getTenantId() : o.getCustNo()));
    }

    static BulkLabelJobDTO toDto(BulkLabelJob j) {
        return BulkLabelJobDTO.builder()
                .id(j.getId())
                .status(j.getStatus())
                .totalCount(j.getTotalCount())
                .successfulCount(j.getSuccessfulCount())
                .failedCount(j.getFailedCount())
                .failureMessage(j.getFailureMessage())
                .createdAt(j.getCreatedAt())
                .startedAt(j.getStartedAt())
                .completedAt(j.getCompletedAt())
                .downloadable(StringUtils.hasText(j.getResultZipBase64()))
                .build();
    }

    private static ApiResponse<BulkLabelJobDTO> success(BulkLabelJobDTO data, String message) {
        return ApiResponse.<BulkLabelJobDTO>builder()
                .status("success").code(200).message(message).data(data).build();
    }

    private static ApiResponse<BulkLabelJobDTO> failure(HttpStatus status, String message) {
        return ApiResponse.<BulkLabelJobDTO>builder()
                .status("error").code(status.value())
                .errorCode(ErrorCode.VALIDATION_ERROR.name())
                .message(message).data(null).build();
    }

    /** Test / test-hook: force the executors to drain — used in tests
     *  to wait for the async work to finish deterministically. Not
     *  called by production code. */
    public void awaitQuiescenceForTests() {
        try {
            dispatchExecutor.shutdown();
            dispatchExecutor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
