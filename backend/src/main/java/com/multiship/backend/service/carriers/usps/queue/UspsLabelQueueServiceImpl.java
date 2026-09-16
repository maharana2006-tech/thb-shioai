package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.dto.UspsLabelQueueMetricsDTO;
import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.model.UspsLabelQueueItem.Status;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    private final UspsLabelQueueRepository repo;

    /**
     * Platform-wide hourly ceiling. Sourced from
     * {@code usps.direct.queue.hourly-cap} (default 55 - safety margin
     * under USPS' documented 60/hour limit). Never zero at runtime -
     * the {@code @Value} default guarantees a sane fallback.
     */
    private final long hourlyCap;

    public UspsLabelQueueServiceImpl(
            UspsLabelQueueRepository repo,
            @Value("${usps.direct.queue.hourly-cap:55}") long hourlyCap) {
        this.repo = repo;
        // Belt-and-braces - a mis-configured 0 would divide-by-zero the
        // estimator; clamp to the 55/hour default in that case.
        this.hourlyCap = hourlyCap <= 0 ? 55L : hourlyCap;
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
        log.info("USPS label queue: enqueued shipment={} tenant={} priority={} id={} est={}",
                request.shipmentId(), tenant, priority, saved.getId(), estStart);
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
                        + "firstStart={} lastComplete={}",
                saved.size(), request.parentOrderNo(), tenant, priority, firstStart, lastComplete);
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
}
