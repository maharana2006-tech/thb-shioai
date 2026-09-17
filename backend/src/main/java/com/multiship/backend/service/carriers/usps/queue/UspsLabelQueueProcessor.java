package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.model.UspsLabelQueueItem.Status;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import com.multiship.backend.service.ratelimit.TokenBucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * USPS_DIRECT PR-F persistent queue processor - runs on a Spring
 * {@code @Scheduled} tick, asks {@link UspsLabelQueueFairScheduler}
 * for the next balanced batch, marks each row {@code PROCESSING},
 * invokes Agent 2's {@link LabelProcessCallback} to perform the actual
 * USPS label write, then records DONE (with the tracking number) or
 * FAILED (with the exception message + retry_count++).
 *
 * <h3>Rate-limiting</h3>
 * A single {@link TokenBucket} sized to the configured hourly cap
 * (default 55/hour) gates every dispatch - the tick may pick a batch
 * but drops rows the bucket can't cover this hour. Rows are put back
 * as {@code QUEUED} (unchanged) if the bucket is exhausted; the next
 * tick tries again once tokens have accrued.
 *
 * <h3>Callback wiring</h3>
 * The processor holds no compile-time reference to the label pipeline
 * - Agent 2's wiring class registers a callback via
 * {@link #registerCallback(LabelProcessCallback)}. Ticks that fire
 * before the callback is registered log a warning and skip - this
 * lets the queue core deploy independently of the wiring slice.
 *
 * <h3>Concurrency</h3>
 * A single scheduler thread runs this method (Spring's default
 * {@code TaskScheduler} pool has one thread unless configured
 * otherwise); the callback is invoked sequentially on that thread.
 * The 55/hour cap makes concurrency moot at the platform level - one
 * thread can trivially issue one HTTP call per 65s tick.
 *
 * <h3>PR-G3b - terminal-status event bus</h3>
 * On every DONE / FAILED transition the processor publishes a
 * {@link UspsLabelQueueTerminalEvent} via Spring's
 * {@link ApplicationEventPublisher}. {@link UspsQueueImportRowReconciler}
 * subscribes and stamps the import row's {@code generatedStatus} so a
 * completed queue row unblocks the "row stuck at QUEUED_USPS" state
 * on the import history page (audit finding U5).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "usps.direct.queue.processor.enabled",
        havingValue = "true", matchIfMissing = true)
public class UspsLabelQueueProcessor {

    private final UspsLabelQueueRepository repo;
    private final UspsLabelQueueFairScheduler scheduler;
    /**
     * PR-G3b - Spring's app-event bus. Nullable in unit tests that
     * construct the processor via the two-arg legacy constructor;
     * production wiring uses the three-arg autowired constructor which
     * populates this. Guarded on every publish.
     */
    private final ApplicationEventPublisher eventPublisher;

    /** Batch size per tick - sized so 60 ticks/hour * batchSize keeps
     *  the total under the hourly cap. Default 1 (60/hour) which is
     *  slightly over the 55/hour ceiling but the {@link #limiter}
     *  gates the actual dispatch to keep us honest. */
    @Value("${usps.direct.queue.batch-size:1}")
    private int batchSize;

    /** How many label writes per hour we permit. Sourced from
     *  {@code usps.direct.queue.hourly-cap} (default 55). */
    @Value("${usps.direct.queue.hourly-cap:55}")
    private long hourlyCap;

    /** Token bucket wraps the platform-wide hourly cap. Populated in
     *  {@link #init()} because the {@link #hourlyCap} @Value is not
     *  set until after the constructor runs. */
    private TokenBucket limiter;

    /**
     * Agent-2 wiring point. {@code null} until a wiring class calls
     * {@link #registerCallback(LabelProcessCallback)} - typically from
     * a {@code @PostConstruct}. An {@link AtomicReference} keeps the
     * swap thread-safe even if a future config allows multiple
     * scheduler threads.
     */
    private final AtomicReference<LabelProcessCallback> callback = new AtomicReference<>();

    /**
     * Legacy 2-arg constructor kept for the pre-PR-G3b unit tests that
     * pre-date the event bus. The event publisher defaults to null +
     * the publish path guards on null so those tests keep running
     * without touching Spring's app-event infrastructure.
     */
    public UspsLabelQueueProcessor(UspsLabelQueueRepository repo,
                                   UspsLabelQueueFairScheduler scheduler) {
        this(repo, scheduler, null);
    }

    /**
     * PR-G3b - production constructor. Spring auto-wires all three
     * arguments; the event publisher drives
     * {@link UspsQueueImportRowReconciler}'s cross-flow bridge.
     *
     * <p>{@code @Autowired} explicitly resolves Spring's constructor
     * ambiguity with the legacy 2-arg overload above — without it the
     * container falls through to a zero-arg lookup and blows up with
     * {@code NoSuchMethodException: <init>()} at context load.
     */
    @Autowired
    public UspsLabelQueueProcessor(UspsLabelQueueRepository repo,
                                   UspsLabelQueueFairScheduler scheduler,
                                   ApplicationEventPublisher eventPublisher) {
        this.repo = repo;
        this.scheduler = scheduler;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    void init() {
        // Bucket capacity = hourly cap; refill rate = hourly cap per
        // 60 minutes. tryAcquire returns true up to `cap` times per
        // rolling hour, then blocks until tokens accrue.
        long cap = hourlyCap <= 0 ? 55L : hourlyCap;
        this.limiter = new TokenBucket(cap, cap);
        log.info("UspsLabelQueueProcessor ready - batchSize={} hourlyCap={}/hr", batchSize, cap);
    }

    /**
     * Agent-2 wiring hook. Registers the callback that performs the
     * actual USPS label write. Calling this a second time replaces the
     * previous callback (allows hot-swap during tests + zero-downtime
     * deploys of the wiring class).
     */
    public void registerCallback(LabelProcessCallback callback) {
        this.callback.set(callback);
        log.info("UspsLabelQueueProcessor: label callback registered ({})",
                callback == null ? "null" : callback.getClass().getName());
    }

    /**
     * Scheduled dispatch. Runs every {@code usps.direct.queue.tick-interval-ms}
     * (default 65_000 = one tick per 65s -&gt; 55.4 ticks/hour, matches
     * the 55/hour cap for a batchSize=1 default).
     *
     * <p>The tick is idempotent + fail-open: any exception is logged +
     * swallowed so a bug never wedges the scheduler.
     */
    @Scheduled(fixedDelayString = "${usps.direct.queue.tick-interval-ms:65000}",
            initialDelayString = "${usps.direct.queue.initial-delay-ms:10000}")
    public void tick() {
        try {
            drainOneTick();
        } catch (RuntimeException e) {
            log.error("USPS label queue tick failed: {}", e.toString(), e);
        }
    }

    /**
     * Do one tick's worth of work. Broken out from {@link #tick()} so
     * tests can invoke without the scheduler + get an exception if
     * something unexpected happens.
     */
    void drainOneTick() {
        LabelProcessCallback cb = callback.get();
        if (cb == null) {
            log.warn("USPS label queue tick: no callback registered - skipping "
                    + "(Agent-2 wiring class must call registerCallback())");
            return;
        }
        int size = batchSize <= 0 ? 1 : batchSize;
        List<UspsLabelQueueItem> batch = scheduler.pickNextBatch(size);
        if (batch.isEmpty()) return;

        for (UspsLabelQueueItem item : batch) {
            // Rate limit: acquire a token before firing at USPS. When
            // exhausted, leave the row QUEUED for the next tick.
            if (!limiter.tryAcquire()) {
                log.info("USPS label queue: hourly cap reached ({} /hr) - "
                        + "skipping remaining {} row(s) this tick",
                        hourlyCap, batch.size() - batch.indexOf(item));
                return;
            }
            processOne(item, cb);
        }
    }

    /**
     * Mark a single row {@code PROCESSING}, invoke the callback,
     * record the outcome. Swallows all exceptions from the callback
     * (recorded on the row as {@code FAILED}) so one bad shipment
     * cannot poison the rest of the batch.
     */
    private void processOne(UspsLabelQueueItem item, LabelProcessCallback cb) {
        try {
            item.setStatus(Status.PROCESSING);
            item.setStartedAt(LocalDateTime.now());
            repo.save(item);
        } catch (RuntimeException persistFailure) {
            // Extremely rare - a save on an existing row shouldn't
            // throw unless the DB is down. Log + skip; next tick will
            // re-pick it (still QUEUED in the DB).
            log.error("USPS label queue: could not mark id={} PROCESSING: {}",
                    item.getId(), persistFailure.toString(), persistFailure);
            return;
        }

        String tracking = null;
        Exception failure = null;
        try {
            tracking = cb.process(item);
        } catch (Exception e) {
            failure = e;
        }

        try {
            if (failure == null) {
                item.setStatus(Status.DONE);
                item.setCompletedAt(LocalDateTime.now());
                item.setTrackingNumber(tracking);
                item.setLastError(null);
                repo.save(item);
                log.info("USPS label queue: DONE id={} tenant={} shipment={} tracking={}",
                        item.getId(), item.getTenantCode(), item.getShipmentId(), tracking);
                // PR-G3b - fan the completion out to the reconciler so
                // the originating import row can flip QUEUED_USPS ->
                // GENERATED. Import-batch id is null on manual / bulk
                // paths; the reconciler short-circuits in that case.
                publishTerminal(item, Status.DONE, tracking, null);
            } else {
                item.setStatus(Status.FAILED);
                item.setCompletedAt(LocalDateTime.now());
                item.setRetryCount((item.getRetryCount() == null ? 0 : item.getRetryCount()) + 1);
                item.setLastError(truncate(failure.toString(), 4000));
                repo.save(item);
                log.warn("USPS label queue: FAILED id={} tenant={} shipment={} retry={} err={}",
                        item.getId(), item.getTenantCode(), item.getShipmentId(),
                        item.getRetryCount(), failure.toString());
                // PR-G3b - even FAILED terminal states must bridge back
                // to the import row so ops don't see a permanent
                // QUEUED_USPS state after retries exhaust.
                publishTerminal(item, Status.FAILED, null, item.getLastError());
            }
        } catch (RuntimeException persistFailure) {
            log.error("USPS label queue: could not persist outcome for id={}: {}",
                    item.getId(), persistFailure.toString(), persistFailure);
        }
    }

    /**
     * PR-G3b - fire the terminal-state event onto Spring's app-event
     * bus. Fail-open: the publisher may be absent (legacy 2-arg ctor
     * used by pure-Mockito tests), and even in production a broken
     * listener must not tank the queue drain, so we swallow every
     * exception here at WARN level.
     */
    private void publishTerminal(UspsLabelQueueItem item, Status terminal,
                                 String trackingNumber, String errorMessage) {
        if (eventPublisher == null) return;
        try {
            // For MPS pieces the "order" is the parent order number;
            // for single-label rows shipmentId is the orderNo. Reconciler
            // uses this for logging only - importBatchId drives the join.
            Long orderNo = item.getParentOrderNo() != null
                    ? item.getParentOrderNo()
                    : item.getShipmentId();
            eventPublisher.publishEvent(new UspsLabelQueueTerminalEvent(
                    item.getId(),
                    item.getImportBatchId(),
                    orderNo,
                    terminal,
                    trackingNumber,
                    errorMessage));
        } catch (RuntimeException ex) {
            log.warn("USPS label queue: terminal event publish failed for id={}: {}",
                    item.getId(), ex.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ============================================================
    // PR-F4 - dashboard observability
    // ============================================================

    /**
     * PR-F4 - remaining tokens on the platform-wide TokenBucket for
     * the current rolling hour. Non-mutating peek used by the
     * {@code /admin/usps-direct/dashboard/quota-headroom} endpoint to
     * render the "42 / 55 label calls left this hour" gauge without
     * consuming a token.
     *
     * <p>Returns 0 if the limiter has not been initialised yet (should
     * never happen once {@link #init()} has run, but defensive so the
     * dashboard never NPEs on a cold boot).
     */
    public int getRemainingHourlyQuota() {
        TokenBucket b = this.limiter;
        if (b == null) return 0;
        long tokens = b.availableTokens();
        if (tokens < 0) return 0;
        if (tokens > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) tokens;
    }

    /**
     * PR-F4 - the configured hourly cap so the dashboard can render
     * "remaining / cap" without a second injection point. Reads the
     * same {@code usps.direct.queue.hourly-cap} value the limiter was
     * built with.
     */
    public long getConfiguredHourlyCap() {
        return hourlyCap <= 0 ? 55L : hourlyCap;
    }

    // ============================================================
    // Test hooks
    // ============================================================

    /** Test hook - swap the token-bucket limiter for a stub that
     *  starts pre-drained (or with different capacity). */
    void setLimiterForTest(TokenBucket limiter) {
        this.limiter = limiter;
    }

    /** Test hook - override batch size without a Spring context. */
    void setBatchSizeForTest(int batchSize) {
        this.batchSize = batchSize;
    }

    /** Test hook - reveal the callback for identity assertions. */
    LabelProcessCallback getCallbackForTest() {
        return callback.get();
    }
}
