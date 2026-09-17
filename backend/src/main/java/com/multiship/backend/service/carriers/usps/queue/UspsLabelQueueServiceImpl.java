package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.dto.UspsDashboardMetricsDTO;
import com.multiship.backend.dto.UspsLabelQueueMetricsDTO;
import com.multiship.backend.dto.UspsQuotaHeadroomDTO;
import com.multiship.backend.dto.UspsReconciliationRollupDTO;
import com.multiship.backend.dto.UspsRetryBucketDTO;
import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.model.UspsLabelQueueItem.Status;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import com.multiship.backend.service.carriers.usps.UspsDirectVoidReconciliationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Default {@link UspsLabelQueueService} implementation. See
 * {@code docs/usps-direct-integration.md} PR-F for the design.
 */
@Slf4j
@Service
public class UspsLabelQueueServiceImpl implements UspsLabelQueueService {

    /** Fallback priority when the caller doesn't provide one (matches
     *  the DB default in V61). */
    public static final int DEFAULT_PRIORITY = 100;

    /** Window (in completions) used to compute average processing time. */
    private static final int AVG_PROCESSING_SAMPLE_SIZE = 100;

    /** PR-F4 - default lookback windows when caller passes null / zero. */
    private static final Duration DEFAULT_RETRY_LOOKBACK = Duration.ofHours(24);
    private static final Duration DEFAULT_RECONCILIATION_LOOKBACK = Duration.ofDays(30);

    private final UspsLabelQueueRepository repo;

    /**
     * Platform-wide hourly ceiling. Sourced from
     * {@code usps.direct.queue.hourly-cap} (default 55 - safety margin
     * under USPS' documented 60/hour limit). Never zero at runtime -
     * the {@code @Value} default guarantees a sane fallback.
     */
    private final long hourlyCap;

    /**
     * PR-F4 - optional processor reference for reading the live
     * TokenBucket. {@code null} in unit tests that use the two-arg
     * constructor; Spring picks the four-arg constructor at runtime and
     * populates this. Guarded on every read.
     */
    private final UspsLabelQueueProcessor processor;

    /**
     * PR-F4 - optional reconciliation service for the void-rollup
     * sub-DTO. {@code null} in unit tests; Spring populates via the
     * four-arg constructor. Guarded on every read.
     */
    private final UspsDirectVoidReconciliationService reconciliationService;

    /**
     * Legacy 2-arg constructor kept for the existing unit tests that
     * pre-date PR-F4. The dashboard-specific dependencies default to
     * {@code null} and the corresponding dashboard endpoints are
     * effectively inert (return zeros / empty). Prod always uses the
     * 4-arg constructor via Spring.
     */
    public UspsLabelQueueServiceImpl(
            UspsLabelQueueRepository repo,
            @Value("${usps.direct.queue.hourly-cap:55}") long hourlyCap) {
        this(repo, hourlyCap, null, null);
    }

    /**
     * PR-F4 - production constructor. Spring auto-wires all four
     * arguments. Marked {@code @Autowired} so Spring picks it over
     * the 2-arg legacy constructor even though both are public.
     */
    @Autowired
    public UspsLabelQueueServiceImpl(
            UspsLabelQueueRepository repo,
            @Value("${usps.direct.queue.hourly-cap:55}") long hourlyCap,
            UspsLabelQueueProcessor processor,
            UspsDirectVoidReconciliationService reconciliationService) {
        this.repo = repo;
        // Belt-and-braces - a mis-configured 0 would divide-by-zero the
        // estimator; clamp to the 55/hour default in that case.
        this.hourlyCap = hourlyCap <= 0 ? 55L : hourlyCap;
        this.processor = processor;
        this.reconciliationService = reconciliationService;
    }

    // ============================================================
    // Enqueue
    // ============================================================

    @Override
    @Transactional
    public EnqueueResult enqueue(EnqueueRequest request) {
        validateEnqueue(request);
        String tenant = request.tenantCode().trim();

        // Design decision: unique(shipment_id) guarantees at-most-one
        // live row per shipment. A double-enqueue is a bug we want to
        // catch loudly - reject with IllegalStateException so the
        // caller sees "you already queued this shipment" rather than
        // silently overwriting or duplicating the work.
        Optional<UspsLabelQueueItem> existing = repo.findByShipmentId(request.shipmentId());
        if (existing.isPresent()) {
            UspsLabelQueueItem row = existing.get();
            // Terminal-state rows (DONE / CANCELLED) don't clash for
            // future retries at the semantic level, but the unique
            // constraint is unconditional - callers must clean up the
            // stale row explicitly (documented on the SQL migration).
            throw new IllegalStateException(
                    "Shipment " + request.shipmentId() + " is already queued (id="
                            + row.getId() + ", status=" + row.getStatus() + ")");
        }

        int priority = request.priority() <= 0 ? DEFAULT_PRIORITY : request.priority();
        UspsLabelQueueItem toSave = UspsLabelQueueItem.builder()
                .tenantCode(tenant)
                .shipmentId(request.shipmentId())
                .priority(priority)
                .status(Status.QUEUED)
                .retryCount(0)
                // PR-G3b - stamp provenance so the admin dashboard's
                // by-source breakdown + the cancel-cascade lookup both
                // work. Both nullable to preserve back-compat with the
                // 3-arg EnqueueRequest ctor.
                .sourceType(request.sourceType())
                .importBatchId(request.importBatchId())
                .build();

        UspsLabelQueueItem saved;
        try {
            saved = repo.save(toSave);
        } catch (DataIntegrityViolationException race) {
            // Second-writer race lost the unique-constraint check - the
            // other writer got the row. Surface as the same
            // IllegalStateException the "already exists" branch throws.
            throw new IllegalStateException(
                    "Shipment " + request.shipmentId()
                            + " was concurrently enqueued by another writer", race);
        }

        LocalDateTime estStart = computeEstimatedStartAt(tenant);
        log.info("USPS label queue: enqueued shipment={} tenant={} priority={} id={} est={} source={} importBatch={}",
                request.shipmentId(), tenant, priority, saved.getId(), estStart,
                request.sourceType(), request.importBatchId());
        return new EnqueueResult(saved.getId(), estStart);
    }

    private static void validateEnqueue(EnqueueRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        if (request.shipmentId() == null) {
            throw new IllegalArgumentException("shipmentId must not be null");
        }
        if (request.tenantCode() == null || request.tenantCode().isBlank()) {
            throw new IllegalArgumentException("tenantCode must not be blank");
        }
    }

    // ============================================================
    // PR-F2 - MPS batch enqueue
    // ============================================================

    /**
     * PR-F2 implementation. Wrapped in a single {@code @Transactional}
     * so a mid-batch duplicate (either against a pre-existing row or a
     * dup inside the batch itself) rolls back every piece - operators
     * see either 1000 rows or 0, never 400 stragglers to clean up.
     *
     * <p>Estimated completion:
     * <ul>
     *   <li>{@code estimatedFirstStartAt} = current backpressure quote
     *       for this tenant (mirrors {@link #enqueue}'s per-piece quote
     *       for parity with the single-label caller experience).</li>
     *   <li>{@code estimatedLastCompleteAt} = firstStart + N/hourlyCap
     *       hours (the batch's own drain time - avg per-call processing
     *       is ~seconds, negligible vs the hourly ceiling).</li>
     * </ul>
     */
    @Override
    @Transactional
    public EnqueueMpsResult enqueueMps(EnqueueMpsRequest request) {
        validateEnqueueMps(request);
        String tenant = request.tenantCode().trim();
        int priority = request.priority() <= 0 ? DEFAULT_PRIORITY : request.priority();

        // Fail-fast on intra-batch dup shipmentIds so the caller sees a
        // clean IAE rather than a partial insert + DB unique violation
        // wrapped in a Spring integrity exception.
        Set<Long> seen = new HashSet<>(request.pieces().size() * 2);
        for (EnqueueMpsRequest.PieceRequest piece : request.pieces()) {
            if (!seen.add(piece.shipmentId())) {
                throw new IllegalArgumentException(
                        "duplicate shipmentId " + piece.shipmentId()
                                + " inside MPS batch for parentOrderNo=" + request.parentOrderNo());
            }
        }

        List<UspsLabelQueueItem> toPersist = new ArrayList<>(request.pieces().size());
        for (EnqueueMpsRequest.PieceRequest piece : request.pieces()) {
            toPersist.add(UspsLabelQueueItem.builder()
                    .tenantCode(tenant)
                    .shipmentId(piece.shipmentId())
                    .priority(priority)
                    .status(Status.QUEUED)
                    .retryCount(0)
                    .parentOrderNo(request.parentOrderNo())
                    .sequenceNumber(piece.sequenceNumber())
                    // PR-G3b - every piece carries the parent enqueue's
                    // source + importBatchId so the dashboard's by-source
                    // aggregation attributes each of the 1000 pieces to the
                    // triggering caller (bulk / import / manual), not the
                    // splitter, and the cancel-cascade catches all N rows.
                    .sourceType(request.sourceType())
                    .importBatchId(request.importBatchId())
                    .build());
        }

        List<UspsLabelQueueItem> saved;
        try {
            saved = repo.saveAll(toPersist);
        } catch (DataIntegrityViolationException dup) {
            // Cross-batch dup: some shipmentId in this batch already
            // exists in the queue (either a stale row we didn't clean
            // up or a concurrent MPS writer for the same order). Surface
            // as IllegalStateException so callers have ONE exception
            // type to catch across single + MPS enqueue.
            throw new IllegalStateException(
                    "MPS batch for parentOrderNo=" + request.parentOrderNo()
                            + " conflicts with an existing queue row: " + dup.getMostSpecificCause().getMessage(),
                    dup);
        }

        LocalDateTime firstStart = computeEstimatedStartAt(tenant);
        // The batch's own drain time: N pieces / cap per hour, in seconds.
        long batchDrainSec = (long) Math.ceil((double) saved.size() / (double) hourlyCap * 3600.0);
        LocalDateTime lastComplete = firstStart.plusSeconds(batchDrainSec);

        log.info("USPS label queue MPS: enqueued {} pieces for parentOrderNo={} tenant={} priority={} "
                        + "firstStart={} lastComplete={} source={} importBatch={}",
                saved.size(), request.parentOrderNo(), tenant, priority, firstStart, lastComplete,
                request.sourceType(), request.importBatchId());
        return new EnqueueMpsResult(request.parentOrderNo(), saved.size(), firstStart, lastComplete);
    }

    private static void validateEnqueueMps(EnqueueMpsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.tenantCode() == null || request.tenantCode().isBlank()) {
            throw new IllegalArgumentException("tenantCode must not be blank");
        }
        if (request.parentOrderNo() == null) {
            throw new IllegalArgumentException("parentOrderNo must not be null");
        }
        if (request.pieces() == null || request.pieces().isEmpty()) {
            throw new IllegalArgumentException("pieces must not be empty");
        }
        for (int i = 0; i < request.pieces().size(); i++) {
            EnqueueMpsRequest.PieceRequest piece = request.pieces().get(i);
            if (piece == null) {
                throw new IllegalArgumentException("pieces[" + i + "] must not be null");
            }
            if (piece.shipmentId() == null) {
                throw new IllegalArgumentException("pieces[" + i + "].shipmentId must not be null");
            }
            if (piece.sequenceNumber() <= 0) {
                throw new IllegalArgumentException(
                        "pieces[" + i + "].sequenceNumber must be > 0 (got " + piece.sequenceNumber() + ")");
            }
        }
    }

    // ============================================================
    // Metrics
    // ============================================================

    @Override
    @Transactional(readOnly = true)
    public UspsLabelQueueMetricsDTO getMetrics() {
        return buildMetrics(null);
    }

    @Override
    @Transactional(readOnly = true)
    public UspsLabelQueueMetricsDTO getMetricsForTenant(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new IllegalArgumentException("tenantCode must not be blank");
        }
        return buildMetrics(tenantCode.trim());
    }

    private UspsLabelQueueMetricsDTO buildMetrics(String tenantScope) {
        LocalDateTime now = LocalDateTime.now();

        long queuedDepth = (tenantScope == null)
                ? repo.countByStatus(Status.QUEUED)
                : repo.countByStatusAndTenantCode(Status.QUEUED, tenantScope);
        long processingCount = (tenantScope == null)
                ? repo.countByStatus(Status.PROCESSING)
                : repo.countByStatusAndTenantCode(Status.PROCESSING, tenantScope);
        long doneCount = (tenantScope == null)
                ? repo.countByStatus(Status.DONE)
                : repo.countByStatusAndTenantCode(Status.DONE, tenantScope);
        long failedCount = (tenantScope == null)
                ? repo.countByStatus(Status.FAILED)
                : repo.countByStatusAndTenantCode(Status.FAILED, tenantScope);
        long cancelledCount = (tenantScope == null)
                ? repo.countByStatus(Status.CANCELLED)
                : repo.countByStatusAndTenantCode(Status.CANCELLED, tenantScope);

        // Recent completions across ALL tenants - this is a platform-
        // wide statistic even on a tenant-scoped response (throughput
        // gives the same signal to every caller).
        List<UspsLabelQueueItem> recentDone =
                repo.findByStatusAndCompletedAtAfter(Status.DONE, now.minusHours(1));
        long currentHourlyPace = recentDone.size();

        long avgMs = averageProcessingMs(recentDone);
        long oldestAgeSec = oldestQueuedAgeSec(tenantScope, now);
        long estWaitSec = estimateWaitSeconds(queuedDepth, tenantScope);
        LocalDateTime estStart = now.plusSeconds(estWaitSec);

        List<UspsLabelQueueMetricsDTO.TenantDepth> perTenant = null;
        if (tenantScope == null) {
            // Platform-wide response - include the tenant breakdown so
            // the admin dashboard can render "top talkers" without a
            // second round-trip.
            perTenant = repo.findQueueDepthByTenant(Status.QUEUED).stream()
                    .map(td -> UspsLabelQueueMetricsDTO.TenantDepth.builder()
                            .tenantCode(td.getTenantCode())
                            .queuedDepth(td.getDepth() == null ? 0L : td.getDepth())
                            .build())
                    .toList();
        }

        return UspsLabelQueueMetricsDTO.builder()
                .tenantCode(tenantScope)
                .asOf(now)
                .queuedDepth(queuedDepth)
                .processingCount(processingCount)
                .doneCount(doneCount)
                .failedCount(failedCount)
                .cancelledCount(cancelledCount)
                .oldestQueuedAgeSeconds(oldestAgeSec)
                .averageProcessingTimeMs(avgMs)
                .currentHourlyPace(currentHourlyPace)
                .configuredHourlyCap(hourlyCap)
                .estimatedWaitSeconds(estWaitSec)
                .estimatedStartAt(estStart)
                .perTenantDepth(perTenant)
                .build();
    }

    /**
     * Estimated wait for a NEW enqueue landing right now.
     *
     * <p>Platform-wide: {@code queuedDepth / hourlyCap} converted to
     * seconds. Tenant-scoped: divide the platform pace by the number of
     * tenants that currently have queued rows so a fair-share slice
     * covers the tenant's own backlog realistically (rough proxy for
     * the round-robin the {@link UspsLabelQueueFairScheduler} runs at
     * dispatch time).
     */
    private long estimateWaitSeconds(long queuedAhead, String tenantScope) {
        if (queuedAhead <= 0) return 0L;

        double perTenantPacePerHour;
        if (tenantScope == null) {
            perTenantPacePerHour = hourlyCap;
        } else {
            // How many tenants are actively contending? Use the same
            // GROUP BY the metrics response consumes so we don't fire
            // two queries.
            long activeTenants = Math.max(1L,
                    repo.findQueueDepthByTenant(Status.QUEUED).size());
            perTenantPacePerHour = (double) hourlyCap / (double) activeTenants;
        }

        // Ceil so a partial hour of work rounds up (we'd rather quote
        // 62 min than 59 min and miss the SLA).
        double hours = (double) queuedAhead / perTenantPacePerHour;
        return (long) Math.ceil(hours * 3600.0);
    }

    /**
     * Convenience wrapper used by {@link #enqueue} - returns the wall
     * clock the new row should expect to start at.
     */
    private LocalDateTime computeEstimatedStartAt(String tenantScope) {
        long queuedAhead = (tenantScope == null)
                ? repo.countByStatus(Status.QUEUED)
                : repo.countByStatusAndTenantCode(Status.QUEUED, tenantScope);
        long waitSec = estimateWaitSeconds(queuedAhead, tenantScope);
        return LocalDateTime.now().plusSeconds(waitSec);
    }

    /**
     * Average (started_at -&gt; completed_at) over the last
     * {@value #AVG_PROCESSING_SAMPLE_SIZE} completions from
     * {@code recentDone}. Returns 0 when we have no measurable data
     * (rows with a null started_at or completed_at are skipped).
     */
    private static long averageProcessingMs(List<UspsLabelQueueItem> recentDone) {
        if (recentDone == null || recentDone.isEmpty()) return 0L;
        long totalMs = 0;
        int n = 0;
        // Bound the sample so a huge DONE window doesn't hog the loop.
        int limit = Math.min(recentDone.size(), AVG_PROCESSING_SAMPLE_SIZE);
        for (int i = 0; i < limit; i++) {
            UspsLabelQueueItem row = recentDone.get(i);
            if (row.getStartedAt() == null || row.getCompletedAt() == null) continue;
            long ms = Duration.between(row.getStartedAt(), row.getCompletedAt()).toMillis();
            if (ms < 0) continue; // clock skew - drop the sample
            totalMs += ms;
            n++;
        }
        return n == 0 ? 0L : totalMs / n;
    }

    /**
     * Age of the oldest {@code QUEUED} row (in seconds). Zero when the
     * queue is empty for the scope.
     */
    private long oldestQueuedAgeSec(String tenantScope, LocalDateTime now) {
        // Reuse the ORDER-BY hot-path query - the first row is the
        // oldest by (priority, enqueued_at). We just need the enqueued_at
        // of the true oldest; group filter locally to avoid another
        // custom query for a small optimisation.
        List<UspsLabelQueueItem> queued =
                repo.findByStatusOrderByPriorityAscEnqueuedAtAsc(Status.QUEUED);
        LocalDateTime oldest = null;
        for (UspsLabelQueueItem row : queued) {
            if (tenantScope != null && !tenantScope.equals(row.getTenantCode())) continue;
            if (oldest == null || row.getEnqueuedAt().isBefore(oldest)) {
                oldest = row.getEnqueuedAt();
            }
        }
        if (oldest == null) return 0L;
        return Duration.between(oldest, now).getSeconds();
    }

    // ============================================================
    // Cancel
    // ============================================================

    @Override
    @Transactional
    public boolean cancel(Long queueItemId) {
        if (queueItemId == null) return false;
        Optional<UspsLabelQueueItem> maybe = repo.findById(queueItemId);
        if (maybe.isEmpty()) return false;
        UspsLabelQueueItem row = maybe.get();
        if (row.getStatus() != Status.QUEUED) {
            // Idempotent no-op - the caller sees false and can present
            // an "already processing / already done" toast.
            log.info("USPS label queue: cancel skipped for id={} in status={}",
                    queueItemId, row.getStatus());
            return false;
        }
        row.setStatus(Status.CANCELLED);
        row.setCompletedAt(LocalDateTime.now());
        repo.save(row);
        log.info("USPS label queue: cancelled id={} (tenant={} shipment={})",
                queueItemId, row.getTenantCode(), row.getShipmentId());
        return true;
    }

    /**
     * PR-G3b - cascade cancellation of an import batch onto its live
     * queue rows. Only touches {@code QUEUED} rows: {@code PROCESSING}
     * items can't be rolled back without leaking a paid label and
     * terminal rows are immutable audit history.
     *
     * <p>Batched-fetch + per-row save intentionally: we want each
     * cancelled row to carry the correct {@code completedAt} timestamp
     * (per-row so ops can see when the cascade fired) and to give
     * Hibernate a chance to run its optimistic-lock / dirty-check on
     * each row rather than a bulk UPDATE that skips the entity graph.
     * The N is bounded by the import's own row count (typically &lt;1000).
     */
    @Override
    @Transactional
    public int cancelPending(long importBatchId) {
        if (importBatchId <= 0L) {
            return 0;
        }
        List<UspsLabelQueueItem> pending =
                repo.findByImportBatchIdAndStatus(importBatchId, Status.QUEUED);
        if (pending.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int cancelled = 0;
        for (UspsLabelQueueItem row : pending) {
            // Defense in depth - between the fetch above and this save
            // the processor could have flipped the row PROCESSING. Skip
            // silently in that case; the processor owns the DONE/FAILED
            // resolution.
            if (row.getStatus() != Status.QUEUED) continue;
            row.setStatus(Status.CANCELLED);
            row.setCompletedAt(now);
            repo.save(row);
            cancelled++;
        }
        log.info("USPS label queue: cancelPending importBatch={} cancelled {} of {} pending row(s)",
                importBatchId, cancelled, pending.size());
        return cancelled;
    }

    // ============================================================
    // PR-F4 - admin dashboard aggregates
    // ============================================================

    @Override
    @Transactional(readOnly = true)
    public UspsDashboardMetricsDTO getDashboardMetrics(Duration retryLookback,
                                                       Duration reconciliationLookback) {
        Duration retryWin = normalizeLookback(retryLookback, DEFAULT_RETRY_LOOKBACK);
        Duration reconWin = normalizeLookback(reconciliationLookback, DEFAULT_RECONCILIATION_LOOKBACK);

        UspsLabelQueueMetricsDTO queue = buildMetrics(null);
        UspsQuotaHeadroomDTO quota = buildQuotaHeadroom();
        UspsRetryBucketDTO retries = buildRetryBuckets(retryWin);
        UspsReconciliationRollupDTO reconciliation = buildReconciliationRollup(reconWin);
        // PR-G3b - by-source breakdown so ops can see "which surface
        // caused the current spike?" without a second round-trip.
        java.util.Map<String, UspsDashboardMetricsDTO.PerSourceStats> bySource = buildBySourceBreakdown();

        return UspsDashboardMetricsDTO.builder()
                .generatedAt(Instant.now())
                .queue(queue)
                .quota(quota)
                .retries(retries)
                .reconciliation(reconciliation)
                .bySource(bySource)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public UspsRetryBucketDTO getRetryBuckets(Duration lookback) {
        return buildRetryBuckets(normalizeLookback(lookback, DEFAULT_RETRY_LOOKBACK));
    }

    /** Normalise a caller-supplied lookback: null / zero / negative =&gt; default. */
    private static Duration normalizeLookback(Duration lookback, Duration fallback) {
        if (lookback == null) return fallback;
        if (lookback.isZero() || lookback.isNegative()) return fallback;
        return lookback;
    }

    /**
     * PR-F4 - non-mutating quota snapshot. Reads the live TokenBucket
     * via the processor's public accessors; when the processor bean is
     * absent (unit-test path with the 2-arg constructor) reports
     * {@code remainingTokens = 0} with the {@link #hourlyCap}-derived
     * utilisation + next-replenish estimate so the dashboard renders a
     * meaningful "no headroom - processor cold" state instead of blanks.
     */
    private UspsQuotaHeadroomDTO buildQuotaHeadroom() {
        long cap;
        int remaining;
        if (processor != null) {
            cap = processor.getConfiguredHourlyCap();
            remaining = processor.getRemainingHourlyQuota();
        } else {
            // No processor bean - use the service's cap + assume the
            // bucket is drained (worst-case UX for the dashboard).
            cap = hourlyCap;
            remaining = 0;
        }
        // Belt-and-braces cap fallback in the extreme case a nested
        // mis-config leaks a non-positive cap through.
        if (cap <= 0) cap = 55L;

        long nextReplenishSec = 0L;
        if (remaining <= 0) {
            // Rough proxy - one token per (3600 / cap) seconds. Ceils
            // to at least 1s so the FE never renders "0s" while showing
            // 100% utilisation.
            nextReplenishSec = Math.max(1L, (long) Math.ceil(3600.0 / (double) cap));
        }

        BigDecimal utilization;
        if (cap <= 0) {
            utilization = BigDecimal.ZERO;
        } else {
            long consumed = Math.max(0L, cap - Math.max(0, remaining));
            utilization = BigDecimal.valueOf(consumed)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(cap), 1, RoundingMode.HALF_UP);
        }
        return UspsQuotaHeadroomDTO.builder()
                .hourlyCap(cap)
                .remainingTokens(remaining)
                .utilizationPercent(utilization)
                .lastReplenishAt(Instant.now())
                .nextReplenishInSeconds(nextReplenishSec)
                .build();
    }

    /**
     * PR-F4 - per-hour retry / failure histogram over the last
     * {@code lookback} window. Builds a {@link UspsRetryBucketDTO}
     * with one entry per active hour.
     */
    private UspsRetryBucketDTO buildRetryBuckets(Duration lookback) {
        int hours = Math.max(1, (int) Math.min(lookback.toHours(), Integer.MAX_VALUE));
        LocalDateTime start = LocalDateTime.now().minus(lookback);

        List<UspsLabelQueueRepository.HourlyRetryBucket> rows =
                repo.findRetryCountsByHour(start);

        List<UspsRetryBucketDTO.Bucket> buckets = new ArrayList<>(rows.size());
        for (UspsLabelQueueRepository.HourlyRetryBucket row : rows) {
            LocalDateTime hs = row.getHourStart();
            Instant instant = hs == null ? null : hs.toInstant(ZoneOffset.UTC);
            long attempts = row.getAttempts() == null ? 0L : row.getAttempts();
            long retries = row.getRetries() == null ? 0L : row.getRetries();
            long failures = row.getFailures() == null ? 0L : row.getFailures();
            buckets.add(new UspsRetryBucketDTO.Bucket(instant, attempts, retries, failures));
        }
        return UspsRetryBucketDTO.builder()
                .hoursLookback(hours)
                .buckets(buckets)
                .build();
    }

    /**
     * PR-F4 - void-reconciliation rollup. Delegates to
     * {@link UspsDirectVoidReconciliationService#getRollup(Duration)}
     * when the bean is present; returns an empty rollup when absent
     * (unit tests using the 2-arg constructor path).
     */
    private UspsReconciliationRollupDTO buildReconciliationRollup(Duration lookback) {
        if (reconciliationService == null) {
            return UspsReconciliationRollupDTO.builder()
                    .lookbackDays((int) Math.max(1, lookback.toDays()))
                    .voidedShipmentsInWindow(0L)
                    .reconciledApproved(0L)
                    .reconciledDenied(0L)
                    .notYetReconciled(0L)
                    .pendingRefundValue(BigDecimal.ZERO)
                    .currency("USD")
                    .build();
        }
        UspsReconciliationRollupDTO out = reconciliationService.getRollup(lookback);
        // Defensive: never return a null DTO from the composite path.
        return Objects.requireNonNullElseGet(out, () ->
                UspsReconciliationRollupDTO.builder()
                        .lookbackDays((int) Math.max(1, lookback.toDays()))
                        .voidedShipmentsInWindow(0L)
                        .reconciledApproved(0L).reconciledDenied(0L).notYetReconciled(0L)
                        .pendingRefundValue(BigDecimal.ZERO).currency("USD")
                        .build());
    }

    /**
     * PR-G3b - dashboard's by-source aggregation. Groups QUEUED depth
     * by {@code source_type} and layers a full status breakdown on top
     * for each source. Rows without a source_type (pre-G3b backfill
     * window) collapse into an {@code UNKNOWN} bucket via the repo's
     * COALESCE so ops don't see nine tiny groups while the queue
     * drains legacy rows.
     *
     * <p>Returns an empty map when there's nothing queued so JSON
     * consumers see {@code {}} rather than {@code null}.
     */
    private java.util.Map<String, UspsDashboardMetricsDTO.PerSourceStats> buildBySourceBreakdown() {
        java.util.LinkedHashMap<String, UspsDashboardMetricsDTO.PerSourceStats> out =
                new java.util.LinkedHashMap<>();
        List<UspsLabelQueueRepository.SourceDepth> depths = repo.findQueueDepthBySource();
        for (UspsLabelQueueRepository.SourceDepth row : depths) {
            String sourceKey = row.getSourceType() == null ? "UNKNOWN" : row.getSourceType();
            long queued = row.getDepth() == null ? 0L : row.getDepth();
            long processing = 0L;
            long done = 0L;
            long failed = 0L;
            long cancelled = 0L;
            // Only look up per-source status counts for real enum values;
            // the UNKNOWN bucket only carries a QUEUED depth (the other
            // states aren't cheaply enumerable via the source column).
            UspsLabelQueueItem.SourceType typed = tryParseSource(sourceKey);
            if (typed != null) {
                processing = repo.countBySourceTypeAndStatus(typed, Status.PROCESSING);
                done = repo.countBySourceTypeAndStatus(typed, Status.DONE);
                failed = repo.countBySourceTypeAndStatus(typed, Status.FAILED);
                cancelled = repo.countBySourceTypeAndStatus(typed, Status.CANCELLED);
            }
            out.put(sourceKey, UspsDashboardMetricsDTO.PerSourceStats.builder()
                    .sourceType(sourceKey)
                    .queuedDepth(queued)
                    .processingCount(processing)
                    .doneCount(done)
                    .failedCount(failed)
                    .cancelledCount(cancelled)
                    .build());
        }
        return out;
    }

    private static UspsLabelQueueItem.SourceType tryParseSource(String raw) {
        if (raw == null || raw.isBlank() || "UNKNOWN".equalsIgnoreCase(raw)) return null;
        try {
            return UspsLabelQueueItem.SourceType.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
