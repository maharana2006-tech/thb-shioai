package com.multiship.backend.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.service.SystemSettingService;
import com.multiship.backend.service.TenantScopeEnforcer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 follow-up BS-M2 — verifies the per-tenant OpenAI token
 * metering wired into {@link OpenAiClient}. Uses a real
 * {@link SimpleMeterRegistry} plus a JDK {@link HttpServer} so the
 * shared {@code RestClient} makes an actual HTTP round-trip and the
 * response {@code usage} block is parsed the same way it would be
 * against the real OpenAI API.
 */
class OpenAiClientMetricsTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> nextBody = new AtomicReference<>();
    private final AtomicInteger nextStatus = new AtomicInteger(200);
    private SystemSettingService systemSettings;
    private TenantScopeEnforcer tenantScope;
    private SimpleMeterRegistry registry;
    private ObjectProvider<MeterRegistry> registryProvider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::handle);
        server.start();
        port = server.getAddress().getPort();

        systemSettings = Mockito.mock(SystemSettingService.class);
        // No DB-stored key so the env-var fallback (constructor arg below) is used.
        when(systemSettings.getDecrypted(anyString())).thenReturn(Optional.empty());

        tenantScope = Mockito.mock(TenantScopeEnforcer.class);
        when(tenantScope.resolveScope()).thenReturn(Optional.of("acme"));

        registry = new SimpleMeterRegistry();
        registryProvider = Mockito.mock(ObjectProvider.class);
        when(registryProvider.getIfAvailable()).thenReturn(registry);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        // Drain the request body so keep-alive stays healthy.
        ex.getRequestBody().readAllBytes();
        byte[] body = nextBody.get().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(nextStatus.get(), body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private OpenAiClient newClient() {
        return new OpenAiClient(
                new ObjectMapper(),
                systemSettings,
                tenantScope,
                registryProvider,
                "test-api-key",
                "gpt-4o-mini",
                "http://127.0.0.1:" + port);
    }

    @Test
    void successRecordsTokenCountersAndSuccessOutcome() {
        // Valid OpenAI response — content is a JSON object as our system
        // prompt requires; usage block carries the token counts BS-M2
        // must surface.
        nextStatus.set(200);
        nextBody.set("""
                {
                  "choices": [ { "message": { "content": "{\\"ok\\":true}" } } ],
                  "usage": { "prompt_tokens": 42, "completion_tokens": 17, "total_tokens": 59 }
                }
                """);

        OpenAiClient client = newClient();
        assertNotNull(client.completeJson("system", "user", "suggest-hs"));

        Counter prompt = registry.get("openai.prompt_tokens")
                .tag("tenant", "acme").tag("endpoint", "suggest-hs").counter();
        Counter completion = registry.get("openai.completion_tokens")
                .tag("tenant", "acme").tag("endpoint", "suggest-hs").counter();
        Counter total = registry.get("openai.total_tokens")
                .tag("tenant", "acme").tag("endpoint", "suggest-hs").counter();
        assertEquals(42.0d, prompt.count(), 0.0001);
        assertEquals(17.0d, completion.count(), 0.0001);
        assertEquals(59.0d, total.count(), 0.0001);

        Counter reqs = registry.get("openai.requests")
                .tag("tenant", "acme").tag("endpoint", "suggest-hs").tag("outcome", "success").counter();
        assertEquals(1.0d, reqs.count(), 0.0001);

        Timer duration = registry.get("openai.request.duration")
                .tag("tenant", "acme").tag("endpoint", "suggest-hs").timer();
        assertEquals(1L, duration.count());
        assertTrue(duration.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) > 0L);
    }

    @Test
    void httpFailureRecordsErrorOutcomeAndNoTokenCounters() {
        // Upstream 500 — RestClient throws, client wraps to 502. We
        // must still increment the request counter with outcome=error
        // and MUST NOT record token counters (no usage block available).
        nextStatus.set(500);
        nextBody.set("{\"error\":\"boom\"}");

        OpenAiClient client = newClient();
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> client.completeJson("system", "user", "parse-address"));
        // 502 BAD_GATEWAY per OpenAiClient's error mapping.
        assertEquals(502, thrown.getStatusCode().value());

        Counter reqs = registry.get("openai.requests")
                .tag("tenant", "acme").tag("endpoint", "parse-address").tag("outcome", "error").counter();
        assertEquals(1.0d, reqs.count(), 0.0001);

        // Timer records the failed attempt too — useful for latency-on-error alerting.
        Timer duration = registry.get("openai.request.duration")
                .tag("tenant", "acme").tag("endpoint", "parse-address").timer();
        assertEquals(1L, duration.count());

        // No token counters emitted for the failure — assert the search finds nothing.
        assertThrows(MeterNotFoundException.class, () -> registry.get("openai.prompt_tokens")
                .tag("tenant", "acme").tag("endpoint", "parse-address").counter());
    }

    @Test
    void platformOperatorFoldsToUnscopedTenantTag() {
        // Empty resolveScope() means the caller is a platform operator
        // (ADMIN / wildcard API key) — no tenant to attribute to. We
        // fold to a bounded literal so the tag never leaks a null or
        // explodes cardinality.
        when(tenantScope.resolveScope()).thenReturn(Optional.empty());
        nextStatus.set(200);
        nextBody.set("""
                {
                  "choices": [ { "message": { "content": "{\\"ok\\":true}" } } ],
                  "usage": { "prompt_tokens": 3, "completion_tokens": 1, "total_tokens": 4 }
                }
                """);

        OpenAiClient client = newClient();
        client.completeJson("system", "user", "review-shipment");

        Counter total = registry.get("openai.total_tokens")
                .tag("tenant", OpenAiClient.TENANT_UNSCOPED).tag("endpoint", "review-shipment").counter();
        assertEquals(4.0d, total.count(), 0.0001);
    }

    @Test
    void legacyTwoArgOverloadUsesEndpointUnknownTag() {
        // Back-compat: callers that never migrated to the 3-arg
        // overload still emit valid metrics, just under a coarse tag.
        nextStatus.set(200);
        nextBody.set("""
                {
                  "choices": [ { "message": { "content": "{\\"ok\\":true}" } } ],
                  "usage": { "prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7 }
                }
                """);

        OpenAiClient client = newClient();
        client.completeJson("system", "user");

        Counter total = registry.get("openai.total_tokens")
                .tag("tenant", "acme").tag("endpoint", OpenAiClient.ENDPOINT_UNKNOWN).counter();
        assertEquals(7.0d, total.count(), 0.0001);
    }

    @Test
    void meterNameOnPrometheusScrapeUsesUnderscores() {
        // BP-M4's Prometheus registry converts dot-form Micrometer names
        // to underscore-form + `_total` for counters, which is how ops
        // will query them (e.g. sum by (tenant) (rate(openai_total_tokens_total[5m]))).
        // With SimpleMeterRegistry we can't scrape /actuator/prometheus,
        // but we can verify the meter IDs use the dot form our code emits
        // — Micrometer's naming convention adapter handles the conversion.
        nextStatus.set(200);
        nextBody.set("""
                {
                  "choices": [ { "message": { "content": "{\\"ok\\":true}" } } ],
                  "usage": { "prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2 }
                }
                """);
        newClient().completeJson("system", "user", "suggest-packaging");

        assertTrue(registry.getMeters().stream()
                .map(Meter::getId).map(io.micrometer.core.instrument.Meter.Id::getName)
                .anyMatch("openai.prompt_tokens"::equals));
        assertTrue(registry.getMeters().stream()
                .map(Meter::getId).map(io.micrometer.core.instrument.Meter.Id::getName)
                .anyMatch("openai.request.duration"::equals));
    }
}
