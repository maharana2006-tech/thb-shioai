package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.BulkLabelJobDTO;
import com.multiship.backend.dto.BulkLabelRequestDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.model.BulkLabelJob;
import com.multiship.backend.repository.BulkLabelJobRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.service.output.DispatchContext;
import com.multiship.backend.service.output.DispatchResult;
import com.multiship.backend.service.output.DocType;
import com.multiship.backend.service.output.OutputDestinationService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    /**
     * Sprint 51 R4 (audit finding #4) — pool sizes are now externalised
     * via application.properties (bulk.worker-concurrency, bulk.max-per-tenant).
     * Pre-R4 these were hardcoded to 4 and 2, capping bulk throughput at
     * ~12 shipments/min/tenant against a 5-15s carrier RTT — an order of
     * magnitude below the 100/min/tenant target. Defaults are set in
     * application.properties and env-overrideable per-deployment via
     * BULK_WORKER_CONCURRENCY / BULK_MAX_PER_TENANT.
     *
     * <p>Instance-field defaults kick in for pure-Mockito unit tests that
     * construct this service via {@code new} — Spring never resolves the
     * {@code @Value} annotations in that path, and {@link #initExecutors()}
     * won't fire either, so {@link #ensureExecutors()} handles the lazy
     * fallback there.
     */
    @Value("${bulk.worker-concurrency:24}")
    private int workerConcurrency = 24;

    @Value("${bulk.max-per-tenant:8}")
    private int maxPerTenant = 8;

    /** HTTP timeout for downloading a label PDF from the carrier's CDN. */
    private static final Duration LABEL_FETCH_TIMEOUT = Duration.ofSeconds(15);

    private final BulkLabelJobRepository jobRepository;
    private final CarrierService carrierService;
    /**
     * Sprint 52 output routing — invoked per generated label so every
     * client that opted into direct delivery gets the bytes without
     * waiting on the ZIP download. Optional (null in pure-unit tests
     * constructed via the 4-arg {@code new}), so all invocations null-check
     * first. Field injection (setter) keeps the pre-Sprint 52 constructor
     * signature intact for existing tests.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OutputDestinationService outputDestinationService;
    /**
     * Sprint 50 Tier 0.5 PR E - guard so a scoped USER cannot enqueue a
     * bulk job containing an order from a foreign tenant. We check every
     * order in the request at submit time; any foreign hit rejects the
     * whole batch (fail-fast, per the existing invariant).
     *
     * <p>Sprint 50 Tier 1 finding #15 — also used at job start to derive
     * the tenant key for {@link #fairExecutor} so one tenant's giant
     * batch can't monopolise the fan-out pool.
     */
    private final OrderRepository orderRepository;
    private final TenantScopeEnforcer tenantScope;

    /** Fan-out executor for the per-order workers. Shared across every
     *  job because job kick-off already runs on its own thread. Built in
     *  {@link #initExecutors()} so the {@code @Value} sizes have resolved. */
    private ExecutorService fanOutExecutor;

    /**
     * Sprint 50 Tier 1 finding #15 — per-tenant fair-share wrapper. Caps
     * each tenant at {@code maxPerTenant} concurrent labels so one
     * tenant's giant batch can't drain the {@code workerConcurrency}-slot
     * pool while another tenant waits. 60s acquire timeout is a safety
     * net; under normal load a permit frees in seconds.
     */
    private com.multiship.backend.service.fairness.FairTenantExecutor fairExecutor;

    @PostConstruct
    void initExecutors() {
        ensureExecutors();
        log.info("BulkLabelServiceImpl fan-out ready: workerConcurrency={} maxPerTenant={}",
                workerConcurrency, maxPerTenant);
    }

    /**
     * Sprint 51 BP-M2 — drain the fan-out + dispatch pools on shutdown so
     * in-flight bulk-label jobs get up to 30s to finish before the JVM
     * exits. Without this, a K8s rolling deploy (SIGTERM → 30s grace →
     * SIGKILL) killed workers mid-carrier-call, leaving the job row stuck
     * in RUNNING and one carrier charge with no local record. Combined
     * with server.shutdown=graceful + spring.lifecycle.timeout-per-shutdown-
     * phase=30s the whole stop dance stays inside the K8s grace window.
     */
    @PreDestroy
    void shutdownExecutors() {
        shutdownGracefully("bulk-label-fanout", fanOutExecutor);
        shutdownGracefully("bulk-label-dispatch", dispatchExecutor);
    }

    private static void shutdownGracefully(String name, ExecutorService pool) {
        if (pool == null) return;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("{} did not drain within 30s — forcing shutdownNow()", name);
                pool.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    /**
     * Lazy fallback for tests that construct this service via {@code new}
     * (Mockito {@code @InjectMocks} / pure JUnit) — in that path Spring
     * never runs {@link #initExecutors()} and the executor fields stay
     * null. Called from every submitAll site so tests exercising the
     * fan-out path get a working executor with the compiled-in defaults.
     * Idempotent + synchronized so concurrent first-callers can't
     * double-create the pool.
     */
    private synchronized void ensureExecutors() {
        if (fairExecutor != null) return;
        this.fanOutExecutor = Executors.newFixedThreadPool(workerConcurrency, r -> {
            Thread t = new Thread(r, "bulk-label-worker");
            t.setDaemon(true);
            return t;
        });
        this.fairExecutor = new com.multiship.backend.service.fairness.FairTenantExecutor(
                fanOutExecutor, maxPerTenant, 60);
    }

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

    /**
     * Sprint 52 — hard cap on batch size. The DTO's {@code @Size(500)}
     * catches this at the controller (400 VALIDATION_ERROR) when the
     * caller sends it via HTTP; the service-level check gives non-HTTP
     * callers (background jobs, tests) the same guarantee and surfaces
     * the actionable {@link ErrorCode#BULK_LIMIT_EXCEEDED} code.
     */
    static final int MAX_BULK_ORDERS = 500;

    @Override
    public ApiResponse<BulkLabelJobDTO> submit(BulkLabelRequestDTO request, String requestedBy) {
        if (request == null) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "request body is required.");
        }

        // F1 — XOR check: exactly one of orderNumbers / identifiers must
        // be populated. Both empty means "no orders" (the pre-F1 400);
        // both set is ambiguous — refuse rather than silently merging.
        boolean hasNumbers = request.getOrderNumbers() != null && !request.getOrderNumbers().isEmpty();
        boolean hasIdentifiers = request.getIdentifiers() != null && !request.getIdentifiers().isEmpty();
        if (!hasNumbers && !hasIdentifiers) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Either orderNumbers or identifiers is required.");
        }
        if (hasNumbers && hasIdentifiers) {
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Send either orderNumbers or identifiers, not both — pick one lookup mode "
                            + "per request. Mix orderNo + orderRef inside identifiers if you need both.");
        }

        // F1 — collapse both lookup modes to a single List<Long> of internal
        // orderNos so the downstream tenant check, persistence, and worker
        // dispatch stay identical to the pre-F1 flow.
        List<Long> resolvedOrderNos;
        try {
            resolvedOrderNos = hasNumbers
                    ? request.getOrderNumbers()
                    : resolveIdentifiers(request.getIdentifiers());
        } catch (IdentifierResolutionException ex) {
            // Fail-fast (matches the tenant-guard invariant): if ANY entry
            // can't resolve, refuse the whole batch and list every offender
            // in one message so the caller can fix them all in one round-trip.
            return failure(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage());
        }

        if (resolvedOrderNos.size() > MAX_BULK_ORDERS) {
            return failure(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.BULK_LIMIT_EXCEEDED,
                    "Bulk batch limited to " + MAX_BULK_ORDERS + " orders — this request has "
                            + resolvedOrderNos.size()
                            + ". Split larger batches into multiple submissions.");
        }

        // Sprint 50 Tier 0.5 PR E - tenant guard. Verify EVERY order in
        // the request belongs to the caller's tenant before enqueuing;
        // any foreign order aborts the whole submission (fail-fast).
        // Platform operators pass through unchanged.
        for (Long orderNo : resolvedOrderNos) {
            if (orderNo == null) continue;
            orderRepository.findByOrderNo(orderNo.intValue()).ifPresent(o ->
                    tenantScope.requireTenantMatch(
                            StringUtils.hasText(o.getTenantId()) ? o.getTenantId() : o.getCustNo()));
        }
        // Persist the job row before we return so the client can poll
        // immediately.
        BulkLabelJob job = new BulkLabelJob();
        job.setOrderNumbers(resolvedOrderNos.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")));
        job.setRequestedBy(requestedBy);
        job.setStatus("PENDING");
        job.setTotalCount(resolvedOrderNos.size());
        job.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        BulkLabelJob saved = jobRepository.save(job);

        // Kick off the worker; return before it starts touching carriers.
        dispatchExecutor.submit(() -> runJob(saved.getId()));

        return success(toDto(saved), "Bulk-label job submitted.");
    }

    /**
     * F1 — resolve a mixed list of {@code {type, value}} identifiers into
     * internal orderNos. Each entry independently:
     *
     * <ul>
     *   <li>{@code orderNo} — parses {@code value} to Long; a non-numeric
     *       value becomes a per-entry error.</li>
     *   <li>{@code orderRef} — looks up
     *       {@link com.multiship.backend.model.Order#getWmsExternalId}
     *       case-insensitively; a miss becomes a per-entry error.</li>
     * </ul>
     *
     * <p>Every unresolved entry is collected and surfaced in one exception
     * message so the caller sees all offenders in one round-trip instead of
     * discovering them one at a time via retry.
     */
    List<Long> resolveIdentifiers(List<com.multiship.backend.dto.BulkLabelIdentifierDTO> identifiers) {
        List<Long> resolved = new java.util.ArrayList<>(identifiers.size());
        List<String> failures = new java.util.ArrayList<>();
        for (int i = 0; i < identifiers.size(); i++) {
            // Capture the loop index for lambda use (must be effectively final).
            final int idx = i;
            com.multiship.backend.dto.BulkLabelIdentifierDTO id = identifiers.get(i);
            if (id == null) {
                failures.add("[" + idx + "] null identifier entry");
                continue;
            }
            String type = id.getType() == null ? "" : id.getType().trim();
            String value = id.getValue() == null ? "" : id.getValue().trim();
            if (value.isEmpty()) {
                failures.add("[" + idx + "] blank value");
                continue;
            }
            switch (type) {
                case "orderNo" -> {
                    try {
                        resolved.add(Long.parseLong(value));
                    } catch (NumberFormatException nfe) {
                        failures.add("[" + idx + "] orderNo '" + value + "' is not numeric");
                    }
                }
                case "orderRef" -> orderRepository.findByWmsExternalIdIgnoreCase(value)
                        .ifPresentOrElse(
                                o -> resolved.add((long) o.getOrderNo()),
                                () -> failures.add("[" + idx + "] orderRef '" + value
                                        + "' not found (wms_external_id lookup miss)"));
                default -> failures.add("[" + idx + "] unknown type '" + type
                        + "' — expected \"orderNo\" or \"orderRef\"");
            }
        }
        if (!failures.isEmpty()) {
            throw new IdentifierResolutionException(
                    "identifiers resolution failed for " + failures.size()
                            + " entr" + (failures.size() == 1 ? "y" : "ies") + ": "
                            + String.join("; ", failures));
        }
        return resolved;
    }

    /** F1 — internal signal for the submit() 400 mapping. Not thrown outside
     *  this service; the message carries the itemised offender list. */
    static final class IdentifierResolutionException extends RuntimeException {
        IdentifierResolutionException(String message) { super(message); }
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
     * fan-out pool ({@code workerConcurrency} labels in flight at once),
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
            // bounds total concurrency; the FairTenantExecutor caps each
            // tenant at MAX_PER_TENANT so a giant batch from tenant A
            // doesn't monopolise while tenant B's small batch waits.
            java.util.List<java.util.concurrent.Callable<OrderOutcome>> tasks = new java.util.ArrayList<>(orderNos.length);
            for (long orderNo : orderNos) {
                tasks.add(() -> processOneOrder(orderNo));
            }
            // Sprint 50 Tier 1 finding #15 — derive tenant key from the
            // first order's tenantId/custNo. Cheap lookup, avoids a schema
            // change on BulkLabelJob. Falls back to requestedBy when the
            // order can't be found (unusual — the batch would fail anyway).
            String tenantKey = resolveTenantKey(orderNos, job.getRequestedBy());
            ensureExecutors();
            java.util.List<java.util.concurrent.Future<OrderOutcome>> futures = fairExecutor.submitAll(tenantKey, tasks);

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
     * Sprint 50 Tier 1 finding #15 — resolve a batch's tenant key for
     * fair-share accounting. Uses the first order's tenantId (fall back
     * to custNo, then requestedBy). Blank/null return means "no tenant
     * scoping" — FairTenantExecutor short-circuits to plain invokeAll.
     */
    private String resolveTenantKey(long[] orderNos, String requestedBy) {
        if (orderRepository == null || orderNos.length == 0) return requestedBy;
        return orderRepository.findByOrderNo((int) orderNos[0])
                .map(o -> {
                    if (o.getTenantId() != null && !o.getTenantId().isBlank()) return o.getTenantId();
                    if (o.getCustNo() != null && !o.getCustNo().isBlank()) return o.getCustNo();
                    return requestedBy;
                })
                .orElse(requestedBy);
    }

    /**
     * Worker body — runs on a fan-out pool thread. Never throws;
     * returns a marker OrderOutcome the caller aggregates.
     *
     * <p>Sprint 52 output routing: on a successful label fetch, this
     * also dispatches through {@link OutputDestinationService} so any
     * client-configured LOCAL_FS / SFTP / PRINTER destination receives
     * the bytes. Failures there DO NOT fail the order — the label has
     * already been generated and paid for; we just log and continue. The
     * DB copy driver persists the bytes so ops can re-drive delivery
     * from the admin page.
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
            dispatchToConfiguredDestinations(orderNo, pdf);
            return new OrderOutcome(orderNo, pdf, label.getTrackingNumber(), null);
        } catch (Exception ex) {
            log.warn("Bulk-label worker: order {} failed: {}", orderNo, ex.getMessage());
            return new OrderOutcome(orderNo, null, null,
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    /**
     * Sprint 52 output routing wire-in. Feeds the generated label bytes
     * through the {@link OutputDestinationService} so every client-
     * configured destination gets the payload (and the always-on DB
     * copy is persisted regardless).
     *
     * <p>Never throws — this is best-effort delivery on top of the
     * existing ZIP path. If the service is not wired (unit-test bean),
     * we short-circuit.
     */
    private void dispatchToConfiguredDestinations(long orderNo, byte[] labelBytes) {
        if (outputDestinationService == null) return;
        if (labelBytes == null || labelBytes.length == 0) return;
        try {
            String clientCode = resolveClientCode(orderNo);
            DispatchContext ctx = new DispatchContext(
                    null,                  // no shipment row from the bulk path yet
                    (int) orderNo,
                    clientCode,
                    "application/pdf",
                    null);
            DispatchResult result = outputDestinationService.dispatch(DocType.LABEL, labelBytes, ctx);
            if (result.getFailureCount() > 0) {
                log.warn("Bulk-label output routing: order {} — {}/{} destinations failed",
                        orderNo, result.getFailureCount(), result.getTotalDestinations());
            }
        } catch (Exception ex) {
            // Never let output-routing failure kill the label path.
            log.warn("Bulk-label output routing: order {} failed: {}", orderNo, ex.getMessage());
        }
    }

    /** Best-effort clientCode lookup for the dispatch context. */
    private String resolveClientCode(long orderNo) {
        if (orderRepository == null) return null;
        return orderRepository.findByOrderNo((int) orderNo)
                .map(o -> StringUtils.hasText(o.getTenantId()) ? o.getTenantId() : o.getCustNo())
                .orElse(null);
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
            return failure(HttpStatus.NOT_FOUND, ErrorCode.VALIDATION_ERROR,
                    "Bulk-label job " + jobId + " not found.");
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

    private static ApiResponse<BulkLabelJobDTO> failure(HttpStatus status, ErrorCode errorCode, String message) {
        return ApiResponse.<BulkLabelJobDTO>builder()
                .status("error").code(status.value())
                .errorCode(errorCode.name())
                .message(message).data(null).build();
    }

    /** Test / test-hook: force the executors to drain — used in tests
     *  to wait for the async work to finish deterministically. Not
     *  called by production code.
     *
     *  <p>Sprint 50 PR M — L1: permanently shuts down {@code dispatchExecutor};
     *  any {@code dispatchExecutor.submit(...)} on this bean AFTER this call
     *  will throw {@code RejectedExecutionException}. Fine for tests that
     *  do one submit and dispose, but a footgun if a test reuses the bean.
     *  If you need a re-usable wait, extend this to snapshot completion
     *  counts and poll rather than close the pool. */
    public void awaitQuiescenceForTests() {
        try {
            dispatchExecutor.shutdown();
            dispatchExecutor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
