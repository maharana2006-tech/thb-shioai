package com.multiship.backend.service.external;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Sprint 50 Tier 1-C — unit tests for {@link IdempotencyService}. Covers
 * the three blocker fixes: concurrent-first-request race (SETNX pending),
 * response-header replay, and fail-closed mode for money-touching
 * endpoints.
 */
class IdempotencyServiceTest {

    private ObjectProvider<StringRedisTemplate> redisProvider;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ObjectMapper objectMapper;
    private IdempotencyService service;

    private static final Long API_KEY_ID = 42L;
    private static final String IDEMPOTENCY_KEY = "req-abc-123";
    private static final String MAIN_KEY = "idem:" + API_KEY_ID + ":" + IDEMPOTENCY_KEY;
    private static final String PENDING_KEY = MAIN_KEY + ":pending";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisProvider = mock(ObjectProvider.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(valueOps);

        // Sprint 50 PR K — service owns its own JavaTimeModule-registered
        // mapper. The test-local `objectMapper` below still matches
        // sufficiently for the round-trip tests since we use plain String
        // bodies here — no LocalDateTime in the CachedResponse fixtures.
        objectMapper = new ObjectMapper();
        service = new IdempotencyService(redisProvider);
    }

    /** Convenience typed handler that just returns a 200 with the given body. */
    private static Supplier<ResponseEntity<ApiResponse<String>>> okHandler(String body) {
        return () -> ResponseEntity.ok(
                ApiResponse.<String>builder()
                        .status("SUCCESS").code(200).message("ok").data(body).build());
    }

    private static TypeReference<ApiResponse<String>> stringTypeRef() {
        return new TypeReference<ApiResponse<String>>() {};
    }

    // ===== 1-4: pass-through paths =====

    @Test
    void noIdempotencyKey_passesThroughToHandler() {
        AtomicInteger calls = new AtomicInteger();
        ResponseEntity<ApiResponse<String>> resp = service.executeOrReplay(
                API_KEY_ID, null, stringTypeRef(),
                () -> { calls.incrementAndGet(); return okHandler("hello").get(); });

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(1, calls.get());
        verifyNoInteractions(valueOps);
    }

    @Test
    void emptyIdempotencyKey_passesThroughToHandler() {
        AtomicInteger calls = new AtomicInteger();
        ResponseEntity<ApiResponse<String>> resp = service.executeOrReplay(
                API_KEY_ID, "   ", stringTypeRef(),
                () -> { calls.incrementAndGet(); return okHandler("hello").get(); });

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(1, calls.get());
        verifyNoInteractions(valueOps);
    }

    @Test
    void noApiKeyId_passesThroughToHandler() {
        AtomicInteger calls = new AtomicInteger();
        ResponseEntity<ApiResponse<String>> resp = service.executeOrReplay(
                null, IDEMPOTENCY_KEY, stringTypeRef(),
                () -> { calls.incrementAndGet(); return okHandler("hello").get(); });

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(1, calls.get());
        verifyNoInteractions(valueOps);
    }

    @Test
    void noRedisWired_passesThroughToHandler() {
        when(redisProvider.getIfAvailable()).thenReturn(null);
        AtomicInteger calls = new AtomicInteger();
        ResponseEntity<ApiResponse<String>> resp = service.executeOrReplay(
                API_KEY_ID, IDEMPOTENCY_KEY, stringTypeRef(),
                () -> { calls.incrementAndGet(); return okHandler("hello").get(); });

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(1, calls.get());
        verifyNoInteractions(valueOps);
    }

    // ===== 5: first-request happy path =====

    @Test
    void firstCall_claimsPendingAndStoresAndReturnsFresh() {
        when(valueOps.setIfAbsent(eq(PENDING_KEY), eq("1"), any(Duration.class)))
                .thenReturn(Boolean.TRUE);
        when(valueOps.get(eq(MAIN_KEY))).thenReturn(null);

        AtomicInteger calls = new AtomicInteger();
        ResponseEntity<ApiResponse<String>> resp = service.executeOrReplay(
                API_KEY_ID, IDEMPOTENCY_KEY, stringTypeRef(),
                () -> { calls.incrementAndGet(); return okHandler("first").get(); });

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("first", resp.getBody().getData());
        assertEquals(1, calls.get());
        assertEquals(IDEMPOTENCY_KEY, resp.getHeaders().getFirst("Idempotency-Key"));

        verify(valueOps).set(eq(MAIN_KEY), anyString(), eq(Duration.ofHours(24)));
    }

    // ===== 6: replay of a fully-cached response =====

    @Test
    void secondCall_afterFirstCompleted_replaysCachedResponse() throws Exception {
        when(valueOps.setIfAbsent(eq(PENDING_KEY), eq("1"), any(Duration.class)))
                .thenReturn(Boolean.FALSE);

        ApiResponse<String> cachedBody = ApiResponse.<String>builder()
                .status("SUCCESS").code(200).message("ok").data("cached-value").build();
        String bodyJson = objectMapper.writeValueAsString(cachedBody);
        String stored = objectMapper.writeValueAsString(
                new IdempotencyService.CachedResponse(200, bodyJson, Map.of()));
        when(valueOps.get(eq(MAIN_KEY))).thenReturn(stored);

        AtomicInteger calls = new AtomicInteger();
        ResponseEntity<ApiResponse<String>> resp = service.executeOrReplay(
                API_KEY_ID, IDEMPOTENCY_KEY, stringTypeRef(),
                () -> { calls.incrementAndGet(); return okHandler("fresh-should-not-run").get(); });

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("cached-value", resp.getBody().getData());
        assertEquals(0, calls.get(), "handler must NOT run on replay");
        assertEquals("true", resp.getHeaders().getFirst("X-Idempotent-Replay"));
        assertEquals(IDEMPOTENCY_KEY, resp.getHeaders().getFirst("Idempotency-Key"));
    }

    // ===== 7: concurrent first request → 409 =====

    @Test
    void secondCall_whileFirstInProgress_returns409() {
        when(valueOps.setIfAbsent(eq(PENDING_KEY), eq("1"), any(Duration.class)))
                .thenReturn(Boolean.FALSE);
        when(valueOps.get(eq(MAIN_KEY))).thenReturn(null);

        AtomicInteger calls = new AtomicInteger();
        ResponseEntity<ApiResponse<String>> resp = service.executeOrReplay(
                API_KEY_ID, IDEMPOTENCY_KEY, stringTypeRef(),
                () -> { calls.incrementAndGet(); return okHandler("nope").get(); });

        assertEquals(HttpStatus.CONFLICT.value(), resp.getStatusCode().value());
        assertEquals(0, calls.get(), "handler must NOT run when first is in flight");
        assertEquals("5", resp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));

        Object bodyObj = resp.getBody();
        assertNotNull(bodyObj);
        assertInstanceOf(ApiResponse.class, bodyObj);
        assertEquals(ErrorCode.IDEMPOTENCY_IN_PROGRESS.name(),
                ((ApiResponse<?>) bodyObj).getErrorCode());
    }

    // ===== 8: namespace regression =====

    @Test
    void differentApiKey_doesNotReplay() {
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(Boolean.TRUE);
        when(valueOps.get(anyString())).thenReturn(null);

        service.executeOrReplay(1L, IDEMPOTENCY_KEY, stringTypeRef(), okHandler("a"));
        service.executeOrReplay(2L, IDEMPOTENCY_KEY, stringTypeRef(), okHandler("b"));

        verify(valueOps).setIfAbsent(eq("idem:1:" + IDEMPOTENCY_KEY + ":pending"),
                eq("1"), any(Duration.class));
        verify(valueOps).setIfAbsent(eq("idem:2:" + IDEMPOTENCY_KEY + ":pending"),
                eq("1"), any(Duration.class));
    }

    // ===== 9: header replay =====

    @Test
    void cachedResponseHeaders_areRestoredOnReplay() throws Exception {
        when(valueOps.setIfAbsent(eq(PENDING_KEY), eq("1"), any(Duration.class)))
                .thenReturn(Boolean.FALSE);

        ApiResponse<String> cachedBody = ApiResponse.<String>builder()
                .status("SUCCESS").code(201).message("created").data("shipment-42").build();
        String bodyJson = objectMapper.writeValueAsString(cachedBody);
        Map<String, List<String>> headers = Map.of(
                "Location", List.of("/shipments/42"),
                "X-Carrier-Ref", List.of("UPS-TR-9001")
        );
        String stored = objectMapper.writeValueAsString(
                new IdempotencyService.CachedResponse(201, bodyJson, headers));
        when(valueOps.get(eq(MAIN_KEY))).thenReturn(stored);

        ResponseEntity<ApiResponse<String>> resp = service.executeOrReplay(
                API_KEY_ID, IDEMPOTENCY_KEY, stringTypeRef(),
                okHandler("should-not-run"));

        assertEquals(201, resp.getStatusCode().value());
        assertEquals("/shipments/42", resp.getHeaders().getFirst("Location"));
        assertEquals("UPS-TR-9001", resp.getHeaders().getFirst("X-Carrier-Ref"));
        assertEquals("true", resp.getHeaders().getFirst("X-Idempotent-Replay"));
        assertEquals(IDEMPOTENCY_KEY, resp.getHeaders().getFirst("Idempotency-Key"));
    }

    // ===== 10: fail-open on Redis error =====

    @Test
    void redisGetThrows_failOpenModeExecutesHandler() {
        when(valueOps.setIfAbsent(eq(PENDING_KEY), eq("1"), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        AtomicInteger calls = new AtomicInteger();
        ResponseEntity<ApiResponse<String>> resp = service.executeOrReplay(
                API_KEY_ID, IDEMPOTENCY_KEY, stringTypeRef(),
                () -> { calls.incrementAndGet(); return okHandler("fail-open").get(); },
                false);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(1, calls.get(), "fail-open: handler runs even on Redis failure");
    }

    // ===== 11: fail-closed on Redis error =====

    @Test
    void redisGetThrows_failClosedModeReturns503() {
        when(valueOps.setIfAbsent(eq(PENDING_KEY), eq("1"), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        AtomicInteger calls = new AtomicInteger();
        ResponseEntity<ApiResponse<String>> resp = service.executeOrReplay(
                API_KEY_ID, IDEMPOTENCY_KEY, stringTypeRef(),
                () -> { calls.incrementAndGet(); return okHandler("nope").get(); },
                true);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), resp.getStatusCode().value());
        assertEquals(0, calls.get(), "fail-closed: handler must NOT run on Redis failure");
        assertEquals("5", resp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));

        Object bodyObj = resp.getBody();
        assertInstanceOf(ApiResponse.class, bodyObj);
        assertEquals(ErrorCode.IDEMPOTENCY_UNAVAILABLE.name(),
                ((ApiResponse<?>) bodyObj).getErrorCode());
    }

    // ===== 12: store failure after successful handler must not fail the request =====

    @Test
    void redisStoreThrows_freshResponseStillReturned() {
        when(valueOps.setIfAbsent(eq(PENDING_KEY), eq("1"), any(Duration.class)))
                .thenReturn(Boolean.TRUE);
        when(valueOps.get(eq(MAIN_KEY))).thenReturn(null);
        doThrow(new RuntimeException("redis flaky on write"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        AtomicInteger calls = new AtomicInteger();
        ResponseEntity<ApiResponse<String>> resp = service.executeOrReplay(
                API_KEY_ID, IDEMPOTENCY_KEY, stringTypeRef(),
                () -> { calls.incrementAndGet(); return okHandler("success-anyway").get(); },
                true);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("success-anyway", resp.getBody().getData());
        assertEquals(1, calls.get());
    }
}
