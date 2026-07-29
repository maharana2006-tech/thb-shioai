package com.multiship.backend.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Audit-fix #10 — bounded @Async pool. Guards the executor shape so
 * a future refactor can't silently drop us back onto
 * SimpleAsyncTaskExecutor (an unbounded thread-per-call spawner).
 */
class AsyncConfigTest {

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) executor.shutdown();
    }

    @Test
    void executor_respectsCorePoolAndMaxPoolSizes() {
        executor = AsyncConfig.buildExecutor(2, 4, 8, 30);
        assertEquals(2, executor.getCorePoolSize());
        assertEquals(4, executor.getMaxPoolSize());
        assertEquals(30, executor.getKeepAliveSeconds());
    }

    @Test
    void executor_hasNamedThreadPrefix() {
        executor = AsyncConfig.buildExecutor(1, 2, 4, 30);
        // Fire a task and confirm the thread name uses the prefix.
        AtomicInteger seenName = new AtomicInteger(-1);
        CountDownLatch latch = new CountDownLatch(1);
        executor.submit(() -> {
            seenName.set(Thread.currentThread().getName().startsWith("async-") ? 1 : 0);
            latch.countDown();
        });
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS), "task should have run");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
        assertEquals(1, seenName.get(),
                "worker thread name must start with async- (was " + seenName.get() + ")");
    }

    @Test
    void executor_usesCallerRunsOnSaturation() throws Exception {
        // Tiny pool + tiny queue → a burst overflows and the caller
        // executes the task synchronously.
        executor = AsyncConfig.buildExecutor(1, 1, 1, 30);

        // Occupy the single worker with a blocking task.
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch startedBlocking = new CountDownLatch(1);
        executor.submit(() -> {
            startedBlocking.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        });
        assertTrue(startedBlocking.await(2, TimeUnit.SECONDS));

        // Fill the queue (capacity 1).
        executor.submit(() -> {
            try { Thread.sleep(100); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        });

        // The next submit should saturate → CallerRuns picks the calling
        // thread. Capture that: task records the thread name it ran on.
        String callerName = Thread.currentThread().getName();
        String[] ranOn = new String[1];
        executor.submit(() -> ranOn[0] = Thread.currentThread().getName());

        // If CallerRuns fired, ranOn[0] == our caller name (the JUnit
        // worker), not "async-*".
        assertEquals(callerName, ranOn[0],
                "saturation must fall through to CallerRunsPolicy (ran on " + ranOn[0] + ")");

        release.countDown();
    }

    @Test
    void executor_rejectionHandlerIsCallerRuns() {
        executor = AsyncConfig.buildExecutor(1, 1, 1, 30);
        // The underlying ThreadPoolExecutor is exposed via getThreadPoolExecutor().
        ThreadPoolExecutor underlying = executor.getThreadPoolExecutor();
        assertTrue(underlying.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.CallerRunsPolicy,
                "rejection handler must be CallerRunsPolicy, was "
                        + underlying.getRejectedExecutionHandler().getClass().getSimpleName());
    }
}
