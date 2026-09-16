package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.model.UspsLabelQueueItem.Status;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import com.multiship.backend.service.ratelimit.TokenBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito tests for {@link UspsLabelQueueProcessor}. Exercises
 * one {@link UspsLabelQueueProcessor#drainOneTick()} at a time so the
 * assertions can pin exactly what the processor does per tick.
 *
 * <p>The rate-limiter path uses a real {@link TokenBucket} (cheap +
 * deterministic) - a size-0 bucket exhausts immediately for the
 * exhaustion test.
 */
class UspsLabelQueueProcessorTest {

    private UspsLabelQueueRepository repo;
    private UspsLabelQueueFairScheduler scheduler;
    private UspsLabelQueueProcessor processor;

    @BeforeEach
    void setUp() throws Exception {
        repo = mock(UspsLabelQueueRepository.class);
        scheduler = mock(UspsLabelQueueFairScheduler.class);
        processor = new UspsLabelQueueProcessor(repo, scheduler);
        // Simulate @Value defaults - constructor doesn't get them.
        setField(processor, "batchSize", 5);
        setField(processor, "hourlyCap", 55L);
        // Real bucket sized to cap; refills once per hour.
        processor.setLimiterForTest(new TokenBucket(55, 55));
    }

    // ================================================================
    // Happy path
    // ================================================================

    @Test
    void tick_picksBatchMarksProcessingInvokesCallbackAndMarksDone() {
        UspsLabelQueueItem row = queuedRow(10L, "ACME", 100);
        when(scheduler.pickNextBatch(5)).thenReturn(List.of(row));
        when(repo.save(any(UspsLabelQueueItem.class))).thenAnswer(inv -> inv.getArgument(0));

        AtomicInteger invocations = new AtomicInteger();
        processor.registerCallback(item -> {
            invocations.incrementAndGet();
            return "9400111899223197428457";
        });

        processor.drainOneTick();

        assertEquals(1, invocations.get(), "Callback must be invoked exactly once per row");

        // save() called twice: PROCESSING then DONE.
        ArgumentCaptor<UspsLabelQueueItem> cap = ArgumentCaptor.forClass(UspsLabelQueueItem.class);
        verify(repo, times(2)).save(cap.capture());
        List<UspsLabelQueueItem> saves = cap.getAllValues();
        // The processor mutates the same object; both captured references
        // are identical - the terminal state is what we assert.
        assertSame(row, saves.get(0));
        assertSame(row, saves.get(1));
        assertEquals(Status.DONE, row.getStatus());
        assertNotNull(row.getStartedAt());
        assertNotNull(row.getCompletedAt());
        assertEquals("9400111899223197428457", row.getTrackingNumber());
        assertNull(row.getLastError());
    }

    // ================================================================
    // Failure path
    // ================================================================

    @Test
    void tick_callbackThrows_marksFailedAndBumpsRetryCount() {
        UspsLabelQueueItem row = queuedRow(11L, "ACME", 100);
        when(scheduler.pickNextBatch(5)).thenReturn(List.of(row));
        when(repo.save(any(UspsLabelQueueItem.class))).thenAnswer(inv -> inv.getArgument(0));

        processor.registerCallback(item -> {
            throw new RuntimeException("USPS said 503");
        });

        processor.drainOneTick();

        assertEquals(Status.FAILED, row.getStatus());
        assertEquals(1, row.getRetryCount(), "retry_count must increment on failure");
        assertNotNull(row.getLastError());
        assertTrue(row.getLastError().contains("USPS said 503"),
                "lastError must carry the callback's message");
        assertNull(row.getTrackingNumber(), "tracking must remain null on failure");
        assertNotNull(row.getCompletedAt(), "completed_at is stamped even on failure");
    }

    // ================================================================
    // Empty queue
    // ================================================================

    @Test
    void tick_emptyQueue_noCallbackInvocation() {
        when(scheduler.pickNextBatch(5)).thenReturn(List.of());
        AtomicInteger invocations = new AtomicInteger();
        processor.registerCallback(item -> {
            invocations.incrementAndGet();
            return "x";
        });

        processor.drainOneTick();

        assertEquals(0, invocations.get());
        verify(repo, never()).save(any());
    }

    @Test
    void tick_noCallbackRegistered_skipsWithoutCrashing() {
        UspsLabelQueueItem row = queuedRow(12L, "ACME", 100);
        when(scheduler.pickNextBatch(5)).thenReturn(List.of(row));

        // No registerCallback() call.
        processor.drainOneTick();

        // Because the callback is null, we skip BEFORE picking, and
        // never mark the row PROCESSING - it stays QUEUED and next
        // tick (after wiring lands) picks it up.
        verify(scheduler, never()).pickNextBatch(any(Integer.class));
        verify(repo, never()).save(any());
    }

    // ================================================================
    // Batch size
    // ================================================================

    @Test
    void tick_respectsBatchSize() {
        processor.setBatchSizeForTest(2);
        UspsLabelQueueItem a = queuedRow(1L, "ACME", 100);
        UspsLabelQueueItem b = queuedRow(2L, "OTHER", 100);
        when(scheduler.pickNextBatch(2)).thenReturn(List.of(a, b));
        when(repo.save(any(UspsLabelQueueItem.class))).thenAnswer(inv -> inv.getArgument(0));

        AtomicInteger invocations = new AtomicInteger();
        processor.registerCallback(item -> {
            invocations.incrementAndGet();
            return "trk-" + item.getId();
        });

        processor.drainOneTick();

        assertEquals(2, invocations.get());
        verify(scheduler, times(1)).pickNextBatch(2);
    }

    // ================================================================
    // Rate limiter exhaustion
    // ================================================================

    @Test
    void tick_rateLimitExhausted_skipsRowsWithoutProcessing() {
        // Bucket pre-drained: capacity 1, but immediately consumed.
        TokenBucket exhausted = new TokenBucket(1, 1);
        exhausted.tryAcquire(); // drain the sole token
        processor.setLimiterForTest(exhausted);

        UspsLabelQueueItem a = queuedRow(1L, "ACME", 100);
        UspsLabelQueueItem b = queuedRow(2L, "OTHER", 100);
        when(scheduler.pickNextBatch(5)).thenReturn(List.of(a, b));

        AtomicInteger invocations = new AtomicInteger();
        processor.registerCallback(item -> {
            invocations.incrementAndGet();
            return "trk";
        });

        processor.drainOneTick();

        assertEquals(0, invocations.get(),
                "Callback must not fire when the limiter is exhausted");
        verify(repo, never()).save(any());
        // Rows still QUEUED - next tick tries again after tokens accrue.
        assertEquals(Status.QUEUED, a.getStatus());
        assertEquals(Status.QUEUED, b.getStatus());
    }

    @Test
    void tick_partiallyExhaustedLimiter_processesUpToBucketCapacity() {
        // Capacity 1 (fresh, one token available): one row processes,
        // one row skipped.
        processor.setLimiterForTest(new TokenBucket(1, 1));

        UspsLabelQueueItem a = queuedRow(1L, "ACME", 100);
        UspsLabelQueueItem b = queuedRow(2L, "OTHER", 100);
        when(scheduler.pickNextBatch(5)).thenReturn(List.of(a, b));
        when(repo.save(any(UspsLabelQueueItem.class))).thenAnswer(inv -> inv.getArgument(0));

        AtomicInteger invocations = new AtomicInteger();
        processor.registerCallback(item -> {
            invocations.incrementAndGet();
            return "trk-" + item.getId();
        });

        processor.drainOneTick();

        assertEquals(1, invocations.get(),
                "Only one row should fit the exhausted-bucket budget");
        assertEquals(Status.DONE, a.getStatus());
        assertEquals(Status.QUEUED, b.getStatus(), "Second row must stay QUEUED");
    }

    // ================================================================
    // registerCallback
    // ================================================================

    @Test
    void registerCallback_swappedCallback_isPickedUpNextTick() {
        UspsLabelQueueItem row = queuedRow(13L, "ACME", 100);
        when(scheduler.pickNextBatch(5)).thenReturn(List.of(row));
        when(repo.save(any(UspsLabelQueueItem.class))).thenAnswer(inv -> inv.getArgument(0));

        LabelProcessCallback first = item -> "first-tracking";
        LabelProcessCallback second = item -> "second-tracking";
        processor.registerCallback(first);
        assertSame(first, processor.getCallbackForTest());

        processor.registerCallback(second);
        assertSame(second, processor.getCallbackForTest());

        processor.drainOneTick();
        assertEquals("second-tracking", row.getTrackingNumber(),
                "Swapped callback must be the one invoked");
    }

    // ================================================================
    // helpers
    // ================================================================

    private static UspsLabelQueueItem queuedRow(long id, String tenant, int priority) {
        return UspsLabelQueueItem.builder()
                .id(id).tenantCode(tenant).shipmentId(2000L + id)
                .priority(priority).status(Status.QUEUED).retryCount(0)
                .build();
    }

    private static void setField(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
