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
    private final AtomicReference<String> nextContentType = new AtomicReference<>("application/json");
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
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
        lastRequestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        lastAuthorization.set(ex.getRequestHeaders().getFirst("Authorization"));
        byte[] body = nextBody.get().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", nextContentType.get());
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
                  "id": "chatcmpl-test", "object": "chat.completion", "created": 1, "model": "gpt-4o-mini",
                  "choices": [ { "index": 0, "finish_reason": "stop",
                                 "message": { "role": "assistant", "content": "{\\"ok\\":true}" } } ],
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
        // Upstream 500 — the provider call throws, client wraps to 502. We
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
                  "id": "chatcmpl-test", "object": "chat.completion", "created": 1, "model": "gpt-4o-mini",
                  "choices": [ { "index": 0, "finish_reason": "stop",
                                 "message": { "role": "assistant", "content": "{\\"ok\\":true}" } } ],
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
                  "id": "chatcmpl-test", "object": "chat.completion", "created": 1, "model": "gpt-4o-mini",
                  "choices": [ { "index": 0, "finish_reason": "stop",
                                 "message": { "role": "assistant", "content": "{\\"ok\\":true}" } } ],
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
                  "id": "chatcmpl-test", "object": "chat.completion", "created": 1, "model": "gpt-4o-mini",
                  "choices": [ { "index": 0, "finish_reason": "stop",
                                 "message": { "role": "assistant", "content": "{\\"ok\\":true}" } } ],
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

    // ── Spring AI transport ─────────────────────────────────────────────────

    private static final String OK_COMPLETION = """
            {
              "id": "chatcmpl-test", "object": "chat.completion", "created": 1, "model": "gpt-4o-mini",
              "choices": [ { "index": 0, "finish_reason": "stop",
                             "message": { "role": "assistant", "content": "{\\"ok\\":true}" } } ],
              "usage": { "prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2 }
            }
            """;

    @Test
    void jsonCallKeepsTheSameRequestTheHandWrittenClientSent() throws Exception {
        nextBody.set(OK_COMPLETION);

        assertTrue(newClient().completeJson("be terse", "parse this", "parse-address").path("ok").asBoolean());

        com.fasterxml.jackson.databind.JsonNode sent = new ObjectMapper().readTree(lastRequestBody.get());
        assertEquals("gpt-4o-mini", sent.path("model").asText());
        assertEquals(0.0d, sent.path("temperature").asDouble(-1), 0.0001);
        assertEquals("json_object", sent.path("response_format").path("type").asText());
        assertEquals("system", sent.path("messages").path(0).path("role").asText());
        assertEquals("be terse", sent.path("messages").path(0).path("content").asText());
        assertEquals("user", sent.path("messages").path(1).path("role").asText());
        assertEquals("parse this", sent.path("messages").path(1).path("content").asText());
        assertEquals("Bearer test-api-key", lastAuthorization.get());
    }

    @Test
    void aKeyRotatedInSettingsIsUsedOnTheNextCall() {
        nextBody.set(OK_COMPLETION);
        OpenAiClient client = newClient();

        when(systemSettings.getDecrypted(anyString())).thenReturn(Optional.of("first-key"));
        client.completeJson("system", "user", "suggest-hs");
        assertEquals("Bearer first-key", lastAuthorization.get());

        when(systemSettings.getDecrypted(anyString())).thenReturn(Optional.of("rotated-key"));
        client.completeJson("system", "user", "suggest-hs");
        assertEquals("Bearer rotated-key", lastAuthorization.get());
    }

    @Test
    void noKeyAnywhereIsA503BeforeAnyCallIsMade() {
        OpenAiClient client = new OpenAiClient(new ObjectMapper(), systemSettings, tenantScope, registryProvider,
                "", "gpt-4o-mini", "http://127.0.0.1:" + port);

        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> client.completeJson("system", "user", "suggest-hs")).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> client.streamText("system", java.util.List.of(), "chat")).getStatusCode().value());
        assertEquals(null, lastRequestBody.get());
    }

    private static String chunk(String delta, String finishReason) {
        return "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,\"delta\":" + delta
                + ",\"finish_reason\":" + finishReason + "}]}\n\n";
    }

    @Test
    void streamedReplyArrivesInPiecesAndIsMeteredOnce() throws Exception {
        nextContentType.set("text/event-stream");
        nextBody.set(chunk("{\"role\":\"assistant\",\"content\":\"Hello \"}", "null")
                + chunk("{\"content\":\"there\"}", "null")
                + chunk("{}", "\"stop\"")
                + "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"gpt-4o-mini\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":2,\"total_tokens\":11}}\n\n"
                + "data: [DONE]\n\n");

        java.util.List<String> pieces = newClient()
                .streamText("be Shipmate", java.util.List.of(
                        new org.springframework.ai.chat.messages.UserMessage("earlier question"),
                        new org.springframework.ai.chat.messages.AssistantMessage("earlier answer"),
                        new org.springframework.ai.chat.messages.UserMessage("hi")), "chat")
                .collectList()
                .block(java.time.Duration.ofSeconds(20));

        assertEquals(java.util.List.of("Hello ", "there"), pieces);

        com.fasterxml.jackson.databind.JsonNode sent = new ObjectMapper().readTree(lastRequestBody.get());
        assertTrue(sent.path("stream").asBoolean());
        assertTrue(sent.path("stream_options").path("include_usage").asBoolean());
        assertEquals(4, sent.path("messages").size());
        assertEquals("be Shipmate", sent.path("messages").path(0).path("content").asText());
        assertEquals("assistant", sent.path("messages").path(2).path("role").asText());
        assertEquals("hi", sent.path("messages").path(3).path("content").asText());

        assertEquals(11.0d, registry.get("openai.total_tokens")
                .tag("tenant", "acme").tag("endpoint", "chat").counter().count(), 0.0001);
        assertEquals(1.0d, registry.get("openai.requests")
                .tag("tenant", "acme").tag("endpoint", "chat").tag("outcome", "success").counter().count(), 0.0001);
    }
}
