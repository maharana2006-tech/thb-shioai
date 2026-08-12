package com.multiship.backend.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.service.external.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Sprint 50 Tier 1-C — end-to-end guard on {@link IdempotencyService}
 * against a real Redis. Proves that:
 * <ol>
 *   <li>a second POST with the same (apiKeyId, Idempotency-Key) replays
 *       the first response identically and does NOT re-run the handler;</li>
 *   <li>two different apiKeyIds using the same Idempotency-Key never
 *       cross-contaminate — namespace isolation holds against real
 *       Spring Data Redis under the covers.</li>
 * </ol>
 *
 * <p>Guarded by {@code INTEGRATION_TESTS=1} via
 * {@link AbstractIntegrationTest}. Container spec: {@code redis:7-alpine}
 * on 6379. Started statically to keep the Spring context cache warm
 * across the suite (see {@code AbstractIntegrationTest} for the
 * rationale on the singleton pattern).
 */
@TestPropertySource(properties = {
        // Sprint 50 Tier 1-C: this test needs Redis autoconfig ON. The
        // base AbstractIntegrationTest excludes it because most integration
        // tests don't care about Redis (and would fail on the empty-host
        // default). Empty override re-enables autoconfig against the
        // container-provided host wired via DynamicPropertySource below.
        "spring.autoconfigure.exclude="
})
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    /**
     * JVM-singleton Redis. Same reasoning as {@code postgres} in
     * {@link AbstractIntegrationTest}: {@code @Container}'s per-class
     * lifecycle breaks Spring's context cache when the port changes
     * between classes. A static-start container has a stable port for
     * every class that reuses the cached context.
     */
    @SuppressWarnings("resource")
    protected static final GenericContainer<?> redis;
    static {
        redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        redis.start();
    }

    @DynamicPropertySource
    static void wireRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private IdempotencyService idempotency;

    @Test
    void firstPostCreatesAndSecondPostReplays() {
        // Fresh key each test run so a rerun without container reset can't
        // hit a stale entry from a previous invocation.
        String idempotencyKey = "int-" + UUID.randomUUID();
        long apiKeyId = 4242L;

        AtomicInteger calls = new AtomicInteger();
        TypeReference<ApiResponse<String>> typeRef = new TypeReference<>() {};

        ResponseEntity<ApiResponse<String>> first = idempotency.executeOrReplay(
                apiKeyId, idempotencyKey, typeRef,
                () -> {
                    calls.incrementAndGet();
                    return ResponseEntity.status(201).body(
                            ApiResponse.<String>builder()
                                    .status("SUCCESS").code(201)
                                    .timestamp(LocalDateTime.now())
                                    .message("Shipment created.")
                                    .data("shipment-9001").build());
                });

        assertEquals(201, first.getStatusCode().value());
        assertEquals("shipment-9001", first.getBody().getData());
        assertEquals(1, calls.get(), "first call must invoke the handler");

        // Second call with the same key + apiKeyId — must replay.
        ResponseEntity<ApiResponse<String>> second = idempotency.executeOrReplay(
                apiKeyId, idempotencyKey, typeRef,
                () -> {
                    calls.incrementAndGet();
                    return ResponseEntity.status(201).body(
                            ApiResponse.<String>builder()
                                    .status("SUCCESS").code(201)
                                    .timestamp(LocalDateTime.now())
                                    .message("should-not-execute")
                                    .data("shipment-9002-should-not-be-seen").build());
                });

        assertEquals(201, second.getStatusCode().value());
        assertEquals("shipment-9001", second.getBody().getData(),
                "replay body must match the first response");
        assertEquals("true", second.getHeaders().getFirst("X-Idempotent-Replay"),
                "replay header must be present");
        assertEquals(1, calls.get(),
                "second call must NOT invoke the handler — replay only");
    }

    @Test
    void differentApiKeys_withSameIdempotencyKey_bothExecute() {
        // Same idempotency key, different apiKeys — the namespace prefix
        // must keep the two entries separate so both handlers run.
        String idempotencyKey = "int-shared-" + UUID.randomUUID();
        TypeReference<ApiResponse<String>> typeRef = new TypeReference<>() {};

        AtomicInteger callsA = new AtomicInteger();
        AtomicInteger callsB = new AtomicInteger();

        ResponseEntity<ApiResponse<String>> a = idempotency.executeOrReplay(
                111L, idempotencyKey, typeRef,
                () -> {
                    callsA.incrementAndGet();
                    return ResponseEntity.ok(ApiResponse.<String>builder()
                            .status("SUCCESS").code(200).message("A").data("value-A").build());
                });

        ResponseEntity<ApiResponse<String>> b = idempotency.executeOrReplay(
                222L, idempotencyKey, typeRef,
                () -> {
                    callsB.incrementAndGet();
                    return ResponseEntity.ok(ApiResponse.<String>builder()
                            .status("SUCCESS").code(200).message("B").data("value-B").build());
                });

        assertEquals(1, callsA.get(), "apiKey 111 handler must run");
        assertEquals(1, callsB.get(), "apiKey 222 handler must run despite shared key");
        assertEquals("value-A", a.getBody().getData());
        assertEquals("value-B", b.getBody().getData());
        assertNotEquals(a.getBody().getData(), b.getBody().getData(),
                "no cross-tenant leak: values must differ");

        // Sprint 50 PR L — bidirectional namespace check: a THIRD apiKey
        // using the same idempotencyKey must ALSO run a fresh handler
        // (i.e. it can't cross-hit A's cached response or B's). Original
        // test only proved A vs B; this closes the "namespace one-way
        // leak" gap the audit flagged.
        AtomicInteger callsC = new AtomicInteger();
        ResponseEntity<ApiResponse<String>> c = idempotency.executeOrReplay(
                333L, idempotencyKey, typeRef,
                () -> {
                    callsC.incrementAndGet();
                    return ResponseEntity.ok(ApiResponse.<String>builder()
                            .status("SUCCESS").code(200).message("C").data("value-C").build());
                });
        assertEquals(1, callsC.get(),
                "apiKey 333 handler must run — namespace isolation is bidirectional");
        assertEquals("value-C", c.getBody().getData(),
                "third caller cannot get replayed A/B response");
    }
}
