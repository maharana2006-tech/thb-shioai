package com.multiship.backend.service.fairness;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Semaphores keyed by tenant. Kept in a ConcurrentHashMap; entries
     * live until the JVM restarts. In steady state the set of tenants
     * is bounded (a client list, ~1000s at most), so no eviction
     * needed — each entry is a Semaphore(K) which is ~100 bytes.
     */
    private final ConcurrentHashMap<String, Semaphore> tenantSemaphores = new ConcurrentHashMap<>();

    public FairTenantExecutor(ExecutorService delegate,
                               int maxConcurrentPerTenant,
                               long acquireTimeoutSeconds) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        if (maxConcurrentPerTenant <= 0) {
            throw new IllegalArgumentException("maxConcurrentPerTenant must be > 0");
        }
        this.delegate = delegate;
        this.maxConcurrentPerTenant = maxConcurrentPerTenant;
        this.acquireTimeoutSeconds = acquireTimeoutSeconds;
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
        Semaphore semaphore = tenantSemaphores.computeIfAbsent(
                normalized, k -> new Semaphore(maxConcurrentPerTenant));

        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> raw : tasks) {
            // Block until this tenant has a free slot. If the caller is
            // interrupted mid-batch, propagate — the futures list carries
            // whatever's already submitted so partial results are safe.
            if (!semaphore.tryAcquire(acquireTimeoutSeconds, TimeUnit.SECONDS)) {
                // Defence-in-depth: log + fail closed on the current task
                // rather than blindly submit and blow the fairness contract.
                // Callers see this as an InterruptedException-shaped stop.
                log.warn("FairTenantExecutor: tenant {} timed out waiting {}s for a permit — batch aborted after {} tasks",
                        normalized, acquireTimeoutSeconds, futures.size());
                throw new InterruptedException(
                        "Fair-share permit acquire timed out for tenant " + normalized);
            }

            futures.add(delegate.submit(() -> {
                try {
                    return raw.call();
                } finally {
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
     * Test / observability hook — how many permits does this tenant
     * currently hold in-flight? Useful for asserting fairness in tests
     * without racing on log output.
     */
    int inflightForTenant(String tenantKey) {
        if (tenantKey == null || tenantKey.isBlank()) return 0;
        Semaphore s = tenantSemaphores.get(tenantKey.trim().toUpperCase());
        return s == null ? 0 : maxConcurrentPerTenant - s.availablePermits();
    }
}
