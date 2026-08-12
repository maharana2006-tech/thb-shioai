package com.multiship.backend.service.fairness;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 50 Tier 1 finding #15 — fair-share unit coverage. Uses a
 * blocking-latch task pattern to hold pool slots so the semaphore
 * behavior is deterministic without racing on wall-clock timing.
 */
class FairTenantExecutorTest {

    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (pool != null) pool.shutdownNow();
    }

    @Test
    void inputOrderPreservedOnCompletion() throws Exception {
        pool = Executors.newFixedThreadPool(4);
        FairTenantExecutor fair = new FairTenantExecutor(pool, 2, 5);

        List<java.util.concurrent.Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            final int n = i;
            tasks.add(() -> n);
        }
        List<Future<Integer>> futures = fair.submitAll("ACME", tasks);
        // Futures come back in input order — matches invokeAll semantics.
        for (int i = 0; i < 5; i++) {
            assertEquals(i, futures.get(i).get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void tenantIsCappedAtMaxConcurrent() throws Exception {
        pool = Executors.newFixedThreadPool(8);   // pool > per-tenant cap
        FairTenantExecutor fair = new FairTenantExecutor(pool, 2, 5);

        CountDownLatch releaseLatch = new CountDownLatch(1);
        AtomicInteger startedForACME = new AtomicInteger();

        // Submit 5 blocking tasks for ACME on a background thread — the
        // caller thread will block at the 3rd submit because ACME's
        // semaphore only has 2 permits.
        List<java.util.concurrent.Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> {
                startedForACME.incrementAndGet();
                releaseLatch.await();
                return null;
            });
        }

        Thread submitter = new Thread(() -> {
            try {
                fair.submitAll("ACME", tasks);
            } catch (InterruptedException ignored) {}
        }, "test-submitter");
        submitter.start();

        // Give the executor a moment to pick up the first 2 tasks.
        Thread.sleep(200);

        // The FairTenantExecutor should have only 2 permits granted, so
        // only 2 tasks are running. The submitter thread is blocked on the
        // 3rd acquire().
        assertEquals(2, startedForACME.get(),
                "ACME must be capped at 2 concurrent (submitter blocked on 3rd)");
        assertEquals(2, fair.inflightForTenant("ACME"));

        // Release the running tasks — the semaphore frees, submitter
        // unblocks, and the remaining 3 tasks flow through in pairs.
        releaseLatch.countDown();
        submitter.join(2000);
        // The submitter thread returns as soon as all 5 futures are
        // submitted; the pool workers may still be picking them up. Wait
        // (with a bound) until every task has actually executed its body.
        long deadline = System.currentTimeMillis() + 2000;
        while (startedForACME.get() < 5 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(5, startedForACME.get(), "all 5 tasks should have run once permits freed");
    }

    @Test
    void twoTenantsGetIndependentSlots() throws Exception {
        pool = Executors.newFixedThreadPool(8);
        FairTenantExecutor fair = new FairTenantExecutor(pool, 2, 5);

        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger acmeStarted = new AtomicInteger();
        AtomicInteger widgetStarted = new AtomicInteger();

        // ACME submits 4 blocking tasks (only 2 will be in-flight).
        List<java.util.concurrent.Callable<Void>> acme = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            acme.add(() -> {
                acmeStarted.incrementAndGet();
                release.await();
                return null;
            });
        }
        Thread acmeSubmitter = new Thread(() -> {
            try { fair.submitAll("ACME", acme); }
            catch (InterruptedException ignored) {}
        }, "acme-submitter");
        acmeSubmitter.start();

        // Wait until ACME occupies its permits.
        Thread.sleep(200);
        assertEquals(2, fair.inflightForTenant("ACME"));

        // WIDGET now submits 2 tasks; each should start immediately
        // because it has its own semaphore.
        List<java.util.concurrent.Callable<Void>> widget = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            widget.add(() -> {
                widgetStarted.incrementAndGet();
                release.await();
                return null;
            });
        }
        fair.submitAll("WIDGET", widget);

        // Give the pool a moment to pick up WIDGET's tasks.
        Thread.sleep(200);
        assertEquals(2, widgetStarted.get(),
                "WIDGET must not be starved — its 2 permits are independent of ACME's");
        assertEquals(2, fair.inflightForTenant("WIDGET"));

        release.countDown();
        acmeSubmitter.join(2000);
    }

    @Test
    void blankTenantSkipsFairShare() throws Exception {
        // A null / blank tenant key short-circuits to plain invokeAll —
        // matches the pre-fix behavior for ad-hoc / platform-owned batches.
        pool = Executors.newFixedThreadPool(4);
        FairTenantExecutor fair = new FairTenantExecutor(pool, 1, 5);

        List<java.util.concurrent.Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int n = i;
            tasks.add(() -> n);
        }
        // With a real tenant + cap=1 this would serialize; with a blank
        // tenant the pool runs them in parallel through invokeAll.
        List<Future<Integer>> futures = fair.submitAll(null, tasks);
        assertEquals(3, futures.size());
        assertEquals(0, fair.inflightForTenant("anything"),
                "no tenant permit was taken");
    }

    @Test
    void saturatedTenantSecondSubmitAborts() throws Exception {
        pool = Executors.newFixedThreadPool(2);
        // Very short acquire timeout so the test doesn't hang if the code
        // is buggy — 1s is generous for CI.
        FairTenantExecutor fair = new FairTenantExecutor(pool, 1, 1);

        CountDownLatch hold = new CountDownLatch(1);
        // Occupy the single permit with a blocking task.
        fair.submitAll("SLOW", List.of(() -> { hold.await(); return null; }));

        // Now try to submit another SLOW task — should time out because
        // the sole permit is held. Sprint 50 PR K: the deadline
        // enforcement throws TenantSaturatedException (RuntimeException)
        // instead of the old ad-hoc InterruptedException — same intent,
        // richer diagnostics for the HTTP caller.
        FairTenantExecutor.TenantSaturatedException caught = null;
        try {
            fair.submitAll("SLOW", List.of(() -> null));
        } catch (FairTenantExecutor.TenantSaturatedException expected) {
            caught = expected;
        }
        assertTrue(caught != null,
                "second SLOW submit must abort with TenantSaturatedException");
        assertEquals("SLOW", caught.getTenantKey());
        assertEquals(0, caught.getSubmittedTasks(), "no tasks made it in");
        assertEquals(1, caught.getTotalTasks());
        hold.countDown();
    }
}
