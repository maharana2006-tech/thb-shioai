package com.multiship.backend.service.fairness;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Sprint 50 Tier 1 finding #15 — per-tenant fair-share wrapper around a
 * bounded {@link ExecutorService}. Prior to this, {@code fanOutExecutor}
 * was shared by every tenant; one tenant submitting a 5000-order batch
 * would occupy every worker for many minutes while another tenant's
 * 5-order batch waited in the queue.
 *
 * <h3>Mechanism</h3>
 * <ol>
 *   <li>Each tenant gets a private {@link Semaphore} with
 *       {@link #maxConcurrentPerTenant} permits.</li>
 *   <li>{@link #submitAll} acquires one permit per task <b>before</b>
 *       forwarding to the underlying pool. Once the tenant has
 *       {@code maxConcurrentPerTenant} tasks in flight, the caller
 *       thread blocks in {@code acquire()} until one finishes and
 *       releases its permit.</li>
 *   <li>Because the blocking happens on the caller thread — NOT inside
 *       the executor — the pool's other slots stay available for
 *       other tenants' tasks the entire time.</li>
 * </ol>
 *
 * <h3>Fairness guarantee</h3>
 * With pool size {@code P} and per-tenant cap {@code K}, at most
 * {@code min(P, K)} of any tenant's tasks are running at once. If two
 * tenants both have {@code >= K} work pending, they each get {@code K}
 * concurrent workers. If one tenant has {@code >= K} and another has
 * fewer, the second tenant gets its work through immediately and the
 * first backfills as its tasks complete.
 *
 * <h3>Ordering</h3>
 * {@link #submitAll} returns futures in <b>input order</b>, matching
 * {@link ExecutorService#invokeAll} semantics — so callers that folded
 * results back into a positional list (BulkLabelServiceImpl,
 * OrderImportServiceImpl) keep working without changes.
 *
 * <h3>Interruption</h3>
 * If the caller is interrupted while blocked on acquire(), the method
 * restores the interrupt flag and returns whatever futures were
 * submitted so far; the caller decides whether to wait on partial
 * results or bail. This matches the fail-open bias of the rest of the
 * codebase — we never silently drop work.
 *
 * <h3>Not for one-off submissions</h3>
 * Use {@link #submitAll} for the multi-task pattern (a batch). Single
 * one-off tasks should keep using the raw executor — the semaphore
 * bookkeeping is per-batch overhead you don't want on the hot path.
 */
@Slf4j
public class FairTenantExecutor {

    /** Underlying pool. Not owned — creator manages its lifecycle. */
    private final ExecutorService delegate;

    /** Max concurrent tasks a single tenant can hold at once. */
    private final int maxConcurrentPerTenant;

    /** How long to wait for a per-tenant permit before giving up and
     *  submitting anyway (defence-in-depth against a stuck permit leak). */
    private final long acquireTimeoutSeconds;

    /** Sprint 50 PR K — how long the CALLER thread is allowed to be
     *  blocked in {@link #submitAll} in total across all task acquires.
     *  Once elapsed, remaining acquires bail out with a
     *  {@link TenantSaturatedException} so a saturated tenant can't
     *  monopolise a Tomcat thread for the entire batch duration. Set
     *  liberally (minutes) when {@code submitAll} is called from a
     *  background dispatcher; keep short (seconds) when called from an
     *  HTTP handler. */
    private final long maxBatchWaitMs;

    /**
     * Semaphores keyed by tenant. Sprint 50 PR L — bounded Caffeine cache
     * so a rotating tenant-key space (e.g. attacker feeding random
     * {@code requestedBy} strings when clientCode is blank) can't OOM the
     * JVM by growing the map without bound. Cap at 10 000 entries with
     * a 24h idle-eviction — well beyond any real client list size, tiny
     * memory footprint (~100 bytes/entry). Semaphores that get evicted
     * while a permit is in flight are safe: the underlying Semaphore
     * object is still reachable from the running Callable, its release
     * still fires, and any new submission for the same tenant just gets
     * a fresh Semaphore (worst case: brief over-cap window for one
     * tenant).
     */
    private static final int MAX_TRACKED_TENANTS = 10_000;
    private final Cache<String, Semaphore> tenantSemaphores = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_TENANTS)
            .expireAfterAccess(Duration.ofHours(24))
            .build();

    public FairTenantExecutor(ExecutorService delegate,
                               int maxConcurrentPerTenant,
                               long acquireTimeoutSeconds) {
        // Backward-compat: batches default to Long.MAX_VALUE ms, matching
        // the pre-PR-K "block indefinitely" behaviour. Callers on HTTP
        // threads should use the 4-arg constructor with a bounded value.
        this(delegate, maxConcurrentPerTenant, acquireTimeoutSeconds, Long.MAX_VALUE);
    }

    public FairTenantExecutor(ExecutorService delegate,
                               int maxConcurrentPerTenant,
                               long acquireTimeoutSeconds,
                               long maxBatchWaitMs) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        if (maxConcurrentPerTenant <= 0) {
            throw new IllegalArgumentException("maxConcurrentPerTenant must be > 0");
        }
        if (maxBatchWaitMs <= 0) {
            throw new IllegalArgumentException("maxBatchWaitMs must be > 0");
        }
        this.delegate = delegate;
        this.maxConcurrentPerTenant = maxConcurrentPerTenant;
        this.acquireTimeoutSeconds = acquireTimeoutSeconds;
        this.maxBatchWaitMs = maxBatchWaitMs;
    }

    /**
     * Submit a batch of tasks all belonging to the same tenant. Blocks
     * as needed so the tenant never holds more than {@link #maxConcurrentPerTenant}
     * running tasks at once, letting other tenants' work interleave.
     *
     * @param tenantKey identifies the tenant for fair-share accounting.
     *                  {@code null} or blank means "no fairness" and
     *                  short-circuits to plain {@code invokeAll} — used
     *                  by ad-hoc / platform-owned batches.
     * @param tasks the batch, in the order the caller wants results back.
     * @return futures in input order, each guaranteed to have been
     *         submitted (blocked if necessary). Callers await via
     *         {@code future.get()} as usual.
     */
    public <T> List<Future<T>> submitAll(String tenantKey, Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        if (tasks.isEmpty()) return List.of();
        // No tenant key → skip fairness (matches the pre-fix behavior for
        // ad-hoc / platform-owned batches with no owning client).
        if (tenantKey == null || tenantKey.isBlank()) {
            return delegate.invokeAll(new ArrayList<>(tasks));
        }

        String normalized = tenantKey.trim().toUpperCase();
        Semaphore semaphore = tenantSemaphores.get(
                normalized, k -> new Semaphore(maxConcurrentPerTenant));

        // Sprint 50 PR L — snapshot SecurityContext + MDC on the caller
        // thread so each task restores them inside the worker. Without
        // this the fanOutExecutor threads run with no auth (audit
        // stampers persist operator=null) and no correlation MDC (log
        // lines can't be traced to their request). Snapshot ONCE per
        // batch — every task in a batch shares the same caller.
        SecurityContext ctxSnapshot = SecurityContextHolder.getContext();
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        List<Future<T>> futures = new ArrayList<>(tasks.size());
        // Sprint 50 PR K — bound the CALLER thread's total time in submitAll
        // so a saturated tenant + big batch can't hold an HTTP thread for
        // hours. Once the deadline passes, subsequent acquires bail via
        // TenantSaturatedException; already-submitted futures are returned
        // in the exception so the caller can decide (release, cancel, etc).
        long deadlineNanos = System.nanoTime()
                + (maxBatchWaitMs == Long.MAX_VALUE
                        ? Long.MAX_VALUE
                        : TimeUnit.MILLISECONDS.toNanos(maxBatchWaitMs));
        for (Callable<T> raw : tasks) {
            long remainingBatchNanos = deadlineNanos == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : Math.max(0L, deadlineNanos - System.nanoTime());
            long perAcquireNanos = Math.min(
                    TimeUnit.SECONDS.toNanos(acquireTimeoutSeconds),
                    remainingBatchNanos);
            if (perAcquireNanos <= 0 || !semaphore.tryAcquire(perAcquireNanos, TimeUnit.NANOSECONDS)) {
                log.warn("FairTenantExecutor: tenant {} could not acquire a permit within batch budget "
                                + "({}ms max) — {} of {} tasks submitted before abort",
                        normalized, maxBatchWaitMs, futures.size(), tasks.size());
                throw new TenantSaturatedException(normalized,
                        futures.size(), tasks.size(),
                        maxConcurrentPerTenant - semaphore.availablePermits(),
                        futures);
            }

            futures.add(delegate.submit(() -> {
                SecurityContext prevCtx = SecurityContextHolder.getContext();
                Map<String, String> prevMdc = MDC.getCopyOfContextMap();
                try {
                    SecurityContextHolder.setContext(ctxSnapshot);
                    if (mdcSnapshot != null) MDC.setContextMap(mdcSnapshot);
                    return raw.call();
                } finally {
                    // Restore whatever the worker thread had (usually
                    // empty) so a subsequent task on the same thread
                    // doesn't inherit ours.
                    SecurityContextHolder.setContext(prevCtx);
                    if (prevMdc != null) MDC.setContextMap(prevMdc);
                    else MDC.clear();
                    semaphore.release();
                }
            }));
        }
        return futures;
    }

    /** Convenience for the single-task path (still fair-share throttled). */
    public <T> Future<T> submit(String tenantKey, Callable<T> task) throws InterruptedException {
        return submitAll(tenantKey, List.of(task)).get(0);
    }

    /**
     * Sprint 50 PR K — thrown by {@link #submitAll} when the caller thread
     * exhausted its per-batch permit-acquire budget. Carries partial
     * progress so the caller can surface it as an actionable 429 (with
     * how many tasks got in) instead of a bare timeout.
     */
    public static class TenantSaturatedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String tenantKey;
        private final int submittedTasks;
        private final int totalTasks;
        private final int inflightAtAbort;
        private final transient List<? extends Future<?>> partialFutures;

        TenantSaturatedException(String tenantKey, int submittedTasks, int totalTasks,
                                 int inflightAtAbort, List<? extends Future<?>> partialFutures) {
            super("Fair-share permit acquire deadline exceeded for tenant "
                    + tenantKey + " (" + submittedTasks + "/" + totalTasks
                    + " tasks submitted, " + inflightAtAbort + " in flight)");
            this.tenantKey = tenantKey;
            this.submittedTasks = submittedTasks;
            this.totalTasks = totalTasks;
            this.inflightAtAbort = inflightAtAbort;
            this.partialFutures = partialFutures;
        }

        public String getTenantKey() { return tenantKey; }
        public int getSubmittedTasks() { return submittedTasks; }
        public int getTotalTasks() { return totalTasks; }
        public int getInflightAtAbort() { return inflightAtAbort; }
        public List<? extends Future<?>> getPartialFutures() { return partialFutures; }
    }

    /**
     * Test / observability hook — how many permits does this tenant
     * currently hold in-flight? Useful for asserting fairness in tests
     * without racing on log output.
     */
    int inflightForTenant(String tenantKey) {
        if (tenantKey == null || tenantKey.isBlank()) return 0;
        Semaphore s = tenantSemaphores.getIfPresent(tenantKey.trim().toUpperCase());
        return s == null ? 0 : maxConcurrentPerTenant - s.availablePermits();
    }
}
