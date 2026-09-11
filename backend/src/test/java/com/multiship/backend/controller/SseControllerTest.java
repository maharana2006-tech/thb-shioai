package com.multiship.backend.controller;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the pure-function helpers in
 * {@link SseController} — the tenant / eventType extractors that
 * decide who sees what. The full SSE endpoint (long-lived HTTP
 * response bound to a live Redis subscriber) is exercised via
 * integration tests in a follow-up commit.
 *
 * <p>The extractors are deliberately cheap substring parsers — not
 * full Jackson — because they run on EVERY relayed message across
 * every open SSE subscription. Firing up an ObjectMapper for each
 * relay is a scaling footgun. Correctness on the shapes our own
 * events emit is the acceptance bar.
 */
class SseControllerTest {

    @Test
    void extractsTenantFromWellFormedPayload() {
        String body = "{\"eventType\":\"job-updated\",\"tenant\":\"ACME\",\"jobId\":42,\"status\":\"COMPLETED\"}";
        Optional<String> tenant = SseController.extractTenant(body);
        assertEquals(Optional.of("ACME"), tenant);
    }

    @Test
    void tenantMissingReturnsEmpty() {
        String body = "{\"eventType\":\"job-updated\",\"jobId\":42,\"status\":\"COMPLETED\"}";
        assertEquals(Optional.empty(), SseController.extractTenant(body));
    }

    @Test
    void tenantNullReturnsEmpty() {
        // Platform-scope events emit tenant=null on the wire — the
        // cheap parser doesn't need to distinguish these from missing
        // (both produce empty Optional, both mean "no tenant filter").
        String body = "{\"eventType\":\"job-updated\",\"tenant\":null,\"jobId\":42}";
        assertEquals(Optional.empty(), SseController.extractTenant(body));
    }

    @Test
    void tenantEmptyStringReturnsEmpty() {
        // Same treatment as tenant=null: an empty string is not a
        // valid tenant scope and should be treated as "no filter".
        String body = "{\"tenant\":\"\",\"jobId\":42}";
        assertEquals(Optional.empty(), SseController.extractTenant(body));
    }

    @Test
    void extractsEventTypeFromWellFormedPayload() {
        String body = "{\"eventType\":\"batch-updated\",\"tenant\":\"ACME\",\"batchId\":7}";
        Optional<String> type = SseController.extractEventType(body);
        assertEquals(Optional.of("batch-updated"), type);
    }

    @Test
    void nullBodyReturnsEmpty() {
        assertEquals(Optional.empty(), SseController.extractTenant(null));
        assertEquals(Optional.empty(), SseController.extractEventType(null));
    }

    @Test
    void malformedBodyReturnsEmptyDoesNotThrow() {
        // The relay path runs inside a Redis subscriber; a parser
        // exception here would poison the whole subscription for
        // every event that follows. Extractor MUST swallow malformed
        // input and return empty.
        String garbage = "not json";
        assertEquals(Optional.empty(), SseController.extractTenant(garbage));
        assertEquals(Optional.empty(), SseController.extractEventType(garbage));
    }

    @Test
    void extractedTenantIsExactCaseFromPayload() {
        // Case-sensitivity matters for downstream equalsIgnoreCase
        // in the tenant filter — we want the source-of-truth casing
        // preserved through the extractor so ops debugging via log
        // sees the raw value.
        String body = "{\"tenant\":\"Client-CamelCase-123\",\"jobId\":1}";
        assertEquals(Optional.of("Client-CamelCase-123"), SseController.extractTenant(body));
    }

    @Test
    void extractorHandlesFieldsInAnyOrder() {
        // Publishers use LinkedHashMap-driven Jackson output, but
        // that's not a contract. Extractor must find the field
        // wherever it lands in the object.
        String body = "{\"jobId\":42,\"tenant\":\"ACME\",\"eventType\":\"job-updated\"}";
        assertEquals(Optional.of("ACME"), SseController.extractTenant(body));
        assertEquals(Optional.of("job-updated"), SseController.extractEventType(body));
    }

    /* -------- Phase 4: Last-Event-Id resume parsing -------- */

    @Test
    void parseReadOffset_nullReturnsLatest() {
        // Fresh subscription — no history to resume from.
        var offset = SseController.parseReadOffset(null);
        assertEquals("$", offset.getOffset(),
                "Missing Last-Event-Id should mean 'only new events from now on'");
    }

    @Test
    void parseReadOffset_blankReturnsLatest() {
        assertEquals("$", SseController.parseReadOffset("").getOffset());
        assertEquals("$", SseController.parseReadOffset("   ").getOffset());
    }

    @Test
    void parseReadOffset_ourInitialSubscribedPingIsNotAStreamId() {
        // The "sub-N" ids we send with the initial 'subscribed' ping
        // aren't Redis Stream IDs. If a browser reconnects with one
        // as Last-Event-Id, we must fall back to latest() rather
        // than let XREAD choke on a malformed offset.
        assertEquals("$", SseController.parseReadOffset("sub-42").getOffset());
    }

    @Test
    void parseReadOffset_wellFormedStreamIdIsHonoured() {
        // Standard Redis Stream ID shape: millis-seq. Should be
        // passed through to XREAD as the resume point.
        var offset = SseController.parseReadOffset("1699999999999-0");
        assertEquals("1699999999999-0", offset.getOffset());
    }

    @Test
    void parseReadOffset_malformedIdFallsBackToLatest() {
        // Belt-and-braces against a client that sends garbage —
        // arbitrary strings must never crash the endpoint.
        assertEquals("$", SseController.parseReadOffset("not an id").getOffset());
        assertEquals("$", SseController.parseReadOffset("-").getOffset());
        assertEquals("$", SseController.parseReadOffset("-42").getOffset());
        assertEquals("$", SseController.parseReadOffset("42-").getOffset());
        assertEquals("$", SseController.parseReadOffset("42-abc").getOffset());
        assertEquals("$", SseController.parseReadOffset("abc-0").getOffset());
    }

    @Test
    void extractorIsFastEnoughForHotPath() {
        // Not a strict perf test — just a smoke that the substring
        // parser handles a realistic event body in well under a ms.
        // A slow extractor would bottleneck every relayed message.
        String body = "{\"eventType\":\"batch-updated\",\"tenant\":\"ACME\","
                + "\"batchId\":7,\"status\":\"COMPLETE\","
                + "\"totalRows\":734,\"savedRows\":734,\"invalidRows\":0}";
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            SseController.extractTenant(body);
            SseController.extractEventType(body);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 500,
                "20k extractions should take well under 500ms; got " + elapsedMs + "ms");
    }
}
