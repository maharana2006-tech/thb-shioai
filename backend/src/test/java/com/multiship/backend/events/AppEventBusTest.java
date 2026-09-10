package com.multiship.backend.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AppEventBus}. Focused on the fire-and-forget
 * contract:
 *
 * <ul>
 *   <li>Publish routes to {@code events.<topic>} channel</li>
 *   <li>Payload is the JSON-serialised event record</li>
 *   <li>Null Redis template (dev/unit-test mode) is a silent no-op</li>
 *   <li>Redis blip does NOT propagate the exception to the caller</li>
 *   <li>Null event is safely ignored</li>
 * </ul>
 */
class AppEventBusTest {

    private StringRedisTemplate redis;
    private AppEventBus bus;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        bus = new AppEventBus(new ObjectMapper(), redis);
    }

    @Test
    void publishRoutesToCorrectChannel() throws Exception {
        BulkLabelJobEvent event = new BulkLabelJobEvent(
                "job-updated", "ACME", 42L, "RUNNING", 100, 30, 2, false);

        bus.publish(event);

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(channel.capture(), payload.capture());
        assertEquals("events.bulk-labels", channel.getValue());
    }

    @Test
    void payloadIsJsonSerializedEvent() throws Exception {
        ImportBatchEvent event = new ImportBatchEvent(
                "batch-updated", "ACME", 7L, "COMPLETE", 100, 100, 0);

        bus.publish(event);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(anyString(), payload.capture());
        String json = payload.getValue();
        assertTrue(json.contains("\"eventType\":\"batch-updated\""), json);
        assertTrue(json.contains("\"tenant\":\"ACME\""), json);
        assertTrue(json.contains("\"batchId\":7"), json);
        assertTrue(json.contains("\"status\":\"COMPLETE\""), json);
    }

    @Test
    void nullRedisTemplateIsSilentNoOp() {
        // Dev / unit-test mode — no Redis wired. Publishing must not
        // throw or block; every downstream feature must degrade to
        // its polling fallback silently.
        AppEventBus noOpBus = new AppEventBus(new ObjectMapper(), null);
        noOpBus.publish(new BulkLabelJobEvent(
                "job-updated", "ACME", 1L, "RUNNING", 0, 0, 0, false));
        // Nothing to verify beyond "did not throw" — Mockito's
        // never-invoked mock is redundant with the null template.
    }

    @Test
    void redisFailureDoesNotPropagateToTheCaller() {
        // Fire-and-forget: a Redis outage must never break a
        // business call. The publisher swallows and logs the failure.
        doThrow(new RuntimeException("simulated Redis outage"))
                .when(redis).convertAndSend(anyString(), any());
        // Should NOT throw — implicit assertion is the absence of an
        // exception escaping to the test harness.
        bus.publish(new BulkLabelJobEvent(
                "job-updated", "ACME", 42L, "RUNNING", 100, 30, 2, false));
    }

    @Test
    void nullEventIsSafeNoOp() {
        bus.publish(null);
        verify(redis, never()).convertAndSend(anyString(), any());
    }

    @Test
    void channelFormatIsConsistentPerTopic() {
        assertEquals("events.bulk-labels", AppEventBus.channelFor("bulk-labels"));
        assertEquals("events.import-batches", AppEventBus.channelFor("import-batches"));
    }
}
