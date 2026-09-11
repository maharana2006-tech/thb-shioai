package com.multiship.backend.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AppEventBus}. Phase 4 transport (Redis
 * Streams) — verifies XADD + XTRIM behaviour + the fire-and-forget
 * contract that a Redis outage never propagates to the caller.
 */
class AppEventBusTest {

    private StringRedisTemplate redis;
    @SuppressWarnings({"rawtypes", "unchecked"})
    private StreamOperations streamOps;
    private AppEventBus bus;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of(1L, 0L));
        bus = new AppEventBus(new ObjectMapper(), redis);
    }

    @Test
    void publishXaddsToConfiguredStreamKey() {
        BulkLabelJobEvent event = new BulkLabelJobEvent(
                "job-updated", "ACME", 42L, "RUNNING", 100, 30, 2, false);

        bus.publish(event);

        ArgumentCaptor<MapRecord> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        MapRecord captured = captor.getValue();
        assertEquals(AppEventBus.STREAM_KEY, captured.getStream(),
                "Every event lands on the single events:all stream so Last-Event-Id resume is trivial");
    }

    @Test
    void payloadEntryContainsTopicAndJsonPayload() {
        ImportBatchEvent event = new ImportBatchEvent(
                "batch-updated", "ACME", 7L, "COMPLETE", 100, 100, 0);

        bus.publish(event);

        ArgumentCaptor<MapRecord> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> body = (java.util.Map<String, String>) captor.getValue().getValue();
        assertEquals("import-batches", body.get(AppEventBus.TOPIC_FIELD),
                "Topic field lets SseController filter per-subscription without JSON parsing");
        String json = body.get(AppEventBus.PAYLOAD_FIELD);
        assertTrue(json.contains("\"eventType\":\"batch-updated\""), json);
        assertTrue(json.contains("\"tenant\":\"ACME\""), json);
        assertTrue(json.contains("\"batchId\":7"), json);
        assertTrue(json.contains("\"status\":\"COMPLETE\""), json);
    }

    @Test
    void publishTrimsStreamToMaxlen() {
        bus.publish(new BulkLabelJobEvent(
                "job-updated", "ACME", 1L, "RUNNING", 0, 0, 0, false));

        // Approximate trimming — the boolean flag = true means "~"
        // (fuzzy, cheap). Locks in that we're not asking Redis to
        // scan the whole stream on every publish.
        verify(streamOps).trim(eq(AppEventBus.STREAM_KEY),
                eq(AppEventBus.STREAM_MAXLEN), eq(true));
    }

    @Test
    void nullRedisTemplateIsSilentNoOp() {
        // Dev / unit-test mode — no Redis wired. Publishing must not
        // throw or block; every downstream feature must degrade to
        // its polling fallback silently.
        AppEventBus noOpBus = new AppEventBus(new ObjectMapper(), null);
        noOpBus.publish(new BulkLabelJobEvent(
                "job-updated", "ACME", 1L, "RUNNING", 0, 0, 0, false));
        // Just verifying no exception escaped is enough — the redis
        // template is null so there's nothing to verify against.
    }

    @Test
    void redisFailureDoesNotPropagateToTheCaller() {
        // Fire-and-forget: a Redis outage must never break a
        // business call. The publisher swallows and logs the failure.
        doThrow(new RuntimeException("simulated Redis outage"))
                .when(streamOps).add(any(MapRecord.class));
        // Should NOT throw — implicit assertion is the absence of an
        // exception escaping to the test harness.
        bus.publish(new BulkLabelJobEvent(
                "job-updated", "ACME", 42L, "RUNNING", 100, 30, 2, false));
    }

    @Test
    void nullEventIsSafeNoOp() {
        bus.publish(null);
        verify(streamOps, never()).add(any(MapRecord.class));
        verify(streamOps, never()).trim(any(String.class), anyLong(), anyBoolean());
    }

    @Test
    void streamKeyIsStableAcrossPublishes() {
        // Two events, same stream key — no per-topic sharding
        // (Phase 4 design: single stream, in-memory filter). Locks
        // in the invariant so a future "let's split streams per
        // topic" refactor doesn't silently break Last-Event-Id
        // resume across topics.
        bus.publish(new BulkLabelJobEvent(
                "job-updated", "ACME", 1L, "RUNNING", 0, 0, 0, false));
        bus.publish(new ImportBatchEvent(
                "batch-updated", "ACME", 2L, "COMPLETE", 10, 10, 0));

        ArgumentCaptor<MapRecord> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps, org.mockito.Mockito.times(2)).add(captor.capture());
        assertEquals(2, captor.getAllValues().size());
        captor.getAllValues().forEach(r ->
                assertEquals(AppEventBus.STREAM_KEY, r.getStream()));
    }
}
