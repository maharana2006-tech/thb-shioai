package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.CarrierConnector.TrackingEvent;
import com.multiship.backend.service.carriers.CarrierConnector.TrackingResult;
import com.multiship.backend.service.carriers.usps.UspsOAuthTokenCache;
import com.multiship.backend.service.carriers.usps.UspsPaymentAuthCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link UspsDirectConnector#trackShipment(String, String, String)}.
 *
 * <p>USPS v3.2 tracking:
 * {@code GET /tracking/v3.2/tracking/{trackingNumber}?expand=DETAIL}.
 *
 * <p>We stub the HTTP layer by subclassing the connector and overriding
 * {@code executeTrackingGet} — matches the "no MockWebServer dependency"
 * convention of the rest of the carriers/ test suite. The stub also
 * overrides {@code sleepBeforeTrackingRetry} so 429-retry tests finish
 * in tens of milliseconds rather than 14+ seconds of real back-off.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Boundary guards: blank tracking number, blank / null / {@code -local-}
 *       token.</li>
 *   <li>Happy paths: DELIVERED + IN_TRANSIT (fixture-driven).</li>
 *   <li>404 → soft "not found" TrackingResult (no throw).</li>
 *   <li>429 → retries with backoff, ultimately succeeds.</li>
 *   <li>CAT vs prod URL routing verified via captured request URL.</li>
 *   <li>Pure parser paths via package-visible {@code parseTrackingResponse}.</li>
 * </ul>
 */
class UspsDirectTrackingTest {

    private CarrierProperties props;
    private ObjectMapper objectMapper;
    private UspsOAuthTokenCache tokenCache;
    private UspsPaymentAuthCache paymentAuthCache;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        objectMapper = new ObjectMapper();
        tokenCache = new UspsOAuthTokenCache(objectMapper);
        paymentAuthCache = new UspsPaymentAuthCache(objectMapper);
        jdbc = mock(JdbcTemplate.class);
    }

    /** Testing subclass that intercepts the HTTP call — lets us feed
     *  canned responses / status codes without a MockWebServer. Records
     *  the URL + token every call for URL-routing assertions. Also
     *  overrides {@code sleepBeforeTrackingRetry} to a no-op so 429
     *  tests don't spend 14+ seconds waiting on real back-off. */
    private static class StubConnector extends UspsDirectConnector {
        final AtomicReference<String> lastUrl = new AtomicReference<>();
        final AtomicReference<String> lastToken = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger sleeps = new AtomicInteger();
        final java.util.Deque<Object> nextResponses = new java.util.ArrayDeque<>();

        StubConnector(CarrierProperties p, ObjectMapper om,
                       UspsOAuthTokenCache tc, UspsPaymentAuthCache pc, JdbcTemplate jdbc) {
            super(p, om, tc, pc, jdbc);
        }

        /** Queue a canned success body for the next call. */
        StubConnector queueBody(String body) {
            nextResponses.add(body);
            return this;
        }

        /** Queue a synthetic HTTP-status exception for the next call.
         *  Uses {@link HttpClientErrorException} (a concrete subclass of
         *  {@link RestClientResponseException}) so we don't depend on
         *  Spring's version-specific static factory. */
        StubConnector queueStatus(HttpStatus status, String body) {
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            nextResponses.add(HttpClientErrorException.create(
                    status, status.getReasonPhrase(),
                    HttpHeaders.EMPTY, bytes, StandardCharsets.UTF_8));
            return this;
        }

        @Override
        String executeTrackingGet(String url, String accessToken) {
            calls.incrementAndGet();
            lastUrl.set(url);
            lastToken.set(accessToken);
            if (nextResponses.isEmpty()) {
                throw new AssertionError("StubConnector: no queued response for call #" + calls.get()
                        + " (url=" + url + ")");
            }
            Object next = nextResponses.poll();
            if (next instanceof RestClientResponseException rex) {
                throw rex;
            }
            return (String) next;
        }

        @Override
        void sleepBeforeTrackingRetry(long millis) {
            // No-op — tests don't need to spend real seconds waiting.
            // We still count invocations so the retry-count assertions
            // can pin the exact number of back-offs performed.
            sleeps.incrementAndGet();
        }
    }

    private StubConnector newStub() {
        return new StubConnector(props, objectMapper, tokenCache, paymentAuthCache, jdbc);
    }

    // ================================================================
    // Boundary guards
    // ================================================================

    @Test
    void blankTrackingNumberThrowsIllegalArgument() {
        StubConnector c = newStub();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> c.trackShipment("", "real-usps-oauth-token", "PRODUCTION"));
        assertTrue(ex.getMessage().contains("tracking number"),
                "message should mention tracking number; got: " + ex.getMessage());
    }

    @Test
    void nullTrackingNumberThrowsIllegalArgument() {
        StubConnector c = newStub();
        assertThrows(IllegalArgumentException.class,
                () -> c.trackShipment(null, "real-usps-oauth-token", "PRODUCTION"));
    }

    @Test
    void blankTokenThrowsWithNotConfiguredMessage() {
        StubConnector c = newStub();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> c.trackShipment("9400111899223197428490", "", "PRODUCTION"));
        assertTrue(ex.getMessage().contains("not configured"),
                "message should say 'not configured'; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("USPS_PLATFORM_CLIENT_ID"),
                "message should name the system-setting keys; got: " + ex.getMessage());
    }

    @Test
    void nullTokenThrowsWithNotConfiguredMessage() {
        StubConnector c = newStub();
        assertThrows(IllegalStateException.class,
                () -> c.trackShipment("9400111899223197428490", null, "PRODUCTION"));
    }

    @Test
    void localFallbackTokenThrowsWithNotConfiguredMessage() {
        StubConnector c = newStub();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> c.trackShipment("9400111899223197428490",
                        "usps-direct-local-ACCT-7788", "PRODUCTION"));
        assertTrue(ex.getMessage().contains("USPS_PLATFORM_CLIENT_ID"),
                "message should point operator at /settings/system; got: " + ex.getMessage());
    }

    // ================================================================
    // Happy paths — DELIVERED + IN_TRANSIT
    // ================================================================

    @Test
    void deliveredFixtureParsesToTrackingResult() throws Exception {
        String fixture = loadFixture("usps/v3/tracking/tracking_delivered.json");
        StubConnector c = newStub().queueBody(fixture);

        TrackingResult result = c.trackShipment(
                "9400111899223197428490", "real-usps-oauth-token", "PRODUCTION");

        assertEquals("9400111899223197428490", result.trackingNumber());
        assertEquals("DELIVERED", result.status());
        assertTrue(result.delivered(), "statusCategory=Delivered should flip delivered=true");
        assertNotNull(result.trackingUrl());
        assertTrue(result.trackingUrl().contains("9400111899223197428490"));
        // ETA joined from expectedDeliveryDate + expectedDeliveryTime
        LocalDateTime expectedEta = LocalDate.of(2026, 9, 15).atTime(13, 0);
        assertEquals(expectedEta, result.estimatedDelivery());
        // Events parsed oldest → newest natively (matches USPS's own ordering)
        assertEquals(4, result.events().size());
        TrackingEvent first = result.events().get(0);
        assertEquals("GX", first.status());
        assertTrue(first.description().contains("possession"));
        assertEquals("DENVER, CO 80202 US", first.location());
        TrackingEvent last = result.events().get(3);
        assertEquals("01", last.status());
        assertTrue(last.description().toUpperCase().contains("DELIVERED"));
        assertEquals("LOUISVILLE, KY 40202 US", last.location());
        // currentLocation = latest event's location.
        assertEquals("LOUISVILLE, KY 40202 US", result.currentLocation());
    }

    @Test
    void inTransitFixtureParsesToTrackingResult() throws Exception {
        String fixture = loadFixture("usps/v3/tracking/tracking_in_transit.json");
        StubConnector c = newStub().queueBody(fixture);

        TrackingResult result = c.trackShipment(
                "9400111899223197428491", "real-usps-oauth-token", "PRODUCTION");

        assertEquals("IN_TRANSIT", result.status());
        assertFalse(result.delivered());
        assertNotNull(result.estimatedDelivery());
        assertEquals(2, result.events().size());
        assertEquals("AUSTIN, TX 78701 US", result.currentLocation());
    }

    // ================================================================
    // 404 — soft "not found"
    // ================================================================

    @Test
    void notFoundReturnsSoftNotFoundResultNoException() throws Exception {
        String notFoundBody = loadFixture("usps/v3/tracking/tracking_not_found.json");
        StubConnector c = newStub().queueStatus(HttpStatus.NOT_FOUND, notFoundBody);

        TrackingResult result = c.trackShipment(
                "9400111899223199999999", "real-usps-oauth-token", "PRODUCTION");

        assertEquals("9400111899223199999999", result.trackingNumber());
        assertEquals("NOT_FOUND", result.status());
        assertFalse(result.delivered());
        assertNotNull(result.trackingUrl(), "even a not-found result carries the URL-only link");
        assertTrue(result.events().isEmpty());
    }

    // ================================================================
    // 429 retries — backoff + eventual success
    // ================================================================

    @Test
    void rateLimit429RetriesThenSucceeds() throws Exception {
        String fixture = loadFixture("usps/v3/tracking/tracking_in_transit.json");
        StubConnector c = newStub()
                .queueStatus(HttpStatus.TOO_MANY_REQUESTS, "{\"error\":\"rate limit\"}")
                .queueStatus(HttpStatus.TOO_MANY_REQUESTS, "{\"error\":\"rate limit\"}")
                .queueBody(fixture);

        TrackingResult result = c.trackShipment(
                "9400111899223197428491", "real-usps-oauth-token", "PRODUCTION");

        assertEquals(3, c.calls.get(),
                "two 429s should have triggered two retries, third call succeeds → 3 total calls");
        assertEquals(2, c.sleeps.get(),
                "one sleep between each 429 → 2 back-offs before the successful third call");
        assertEquals("IN_TRANSIT", result.status());
        assertFalse(result.delivered());
    }

    @Test
    void rateLimit429ExhaustsRetriesThenFallsBackToUrlOnlyStub() throws Exception {
        // MAX_RATE_LIMIT_RETRIES=3, so we need 4 x 429 to exhaust
        // (initial attempt + 3 retries). The 4th response falls through
        // to the "not a 429 anymore, log + null response" branch.
        StubConnector c = newStub()
                .queueStatus(HttpStatus.TOO_MANY_REQUESTS, "still rate limited")
                .queueStatus(HttpStatus.TOO_MANY_REQUESTS, "still rate limited")
                .queueStatus(HttpStatus.TOO_MANY_REQUESTS, "still rate limited")
                .queueStatus(HttpStatus.TOO_MANY_REQUESTS, "still rate limited");

        TrackingResult result = c.trackShipment(
                "9400111899223197428490", "real-usps-oauth-token", "PRODUCTION");

        // 3 retries used up → last 429 hits the "not a 429 anymore"
        // branch of the guard (attempt >= MAX_RATE_LIMIT_RETRIES) and
        // we fall through to the URL-only stub (status=UNKNOWN).
        assertEquals(4, c.calls.get(),
                "initial call + 3 retries = 4 total calls before exhaustion");
        assertEquals("UNKNOWN", result.status());
        assertNotNull(result.trackingUrl());
        assertTrue(result.events().isEmpty());
    }

    // ================================================================
    // CAT vs prod URL routing
    // ================================================================

    @Test
    void sandboxEnvironmentRoutesToCatHost() throws Exception {
        String fixture = loadFixture("usps/v3/tracking/tracking_in_transit.json");
        StubConnector c = newStub().queueBody(fixture);

        c.trackShipment("9400111899223197428491", "real-usps-oauth-token", "SANDBOX");

        String url = c.lastUrl.get();
        assertNotNull(url);
        assertTrue(url.startsWith(UspsOAuthTokenCache.SANDBOX_HOST),
                "SANDBOX should route to apis-tem.usps.com; got: " + url);
        assertTrue(url.contains("/tracking/v3.2/tracking/9400111899223197428491"),
                "URL should target v3.2 endpoint with the tracking number; got: " + url);
        assertTrue(url.contains("expand=DETAIL"),
                "expand=DETAIL is required for per-scan events; got: " + url);
    }

    @Test
    void productionEnvironmentRoutesToProdHost() throws Exception {
        String fixture = loadFixture("usps/v3/tracking/tracking_in_transit.json");
        StubConnector c = newStub().queueBody(fixture);

        c.trackShipment("9400111899223197428491", "real-usps-oauth-token", "PRODUCTION");

        String url = c.lastUrl.get();
        assertTrue(url.startsWith(UspsOAuthTokenCache.PROD_HOST),
                "PRODUCTION should route to apis.usps.com; got: " + url);
    }

    @Test
    void nullEnvironmentDefaultsToProduction() throws Exception {
        String fixture = loadFixture("usps/v3/tracking/tracking_in_transit.json");
        StubConnector c = newStub().queueBody(fixture);

        c.trackShipment("9400111899223197428491", "real-usps-oauth-token", null);

        String url = c.lastUrl.get();
        assertTrue(url.startsWith(UspsOAuthTokenCache.PROD_HOST),
                "null env should default to PRODUCTION host; got: " + url);
    }

    @Test
    void bearerTokenPassedOnRequest() throws Exception {
        String fixture = loadFixture("usps/v3/tracking/tracking_in_transit.json");
        StubConnector c = newStub().queueBody(fixture);
        c.trackShipment("9400111899223197428491", "specific-token-42", "PRODUCTION");
        assertEquals("specific-token-42", c.lastToken.get(),
                "the access token must be forwarded to the HTTP layer");
    }

    // ================================================================
    // Pure parser tests (no HTTP)
    // ================================================================

    @Test
    void parseTrackingResponseToleratesEmptyBody() throws Exception {
        UspsDirectConnector c = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        TrackingResult r = c.parseTrackingResponse("{}", "9400", "https://tools.usps.com/x");
        assertEquals("UNKNOWN", r.status());
        assertFalse(r.delivered());
        assertTrue(r.events().isEmpty());
        assertNull(r.estimatedDelivery());
    }

    @Test
    void parseTrackingResponseToleratesNullBody() throws Exception {
        UspsDirectConnector c = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        TrackingResult r = c.parseTrackingResponse(null, "9400", "https://tools.usps.com/x");
        assertEquals("UNKNOWN", r.status());
    }

    @Test
    void mapStatusCategoryNormalizesUspsBuckets() {
        assertEquals("PRE_SHIPMENT",
                UspsDirectConnector.mapStatusCategory("Pre-Shipment", null));
        assertEquals("IN_TRANSIT",
                UspsDirectConnector.mapStatusCategory("In Transit", null));
        assertEquals("OUT_FOR_DELIVERY",
                UspsDirectConnector.mapStatusCategory("Out for Delivery", null));
        assertEquals("DELIVERED",
                UspsDirectConnector.mapStatusCategory("Delivered", null));
        assertEquals("ALERT",
                UspsDirectConnector.mapStatusCategory("Alert", null));
        assertEquals("RETURN_TO_SENDER",
                UspsDirectConnector.mapStatusCategory("Return to Sender", null));
    }

    @Test
    void mapStatusCategoryFallsBackToSummaryWhenBlank() {
        assertEquals("Item picked up",
                UspsDirectConnector.mapStatusCategory(null, "Item picked up"));
        assertEquals("Item picked up",
                UspsDirectConnector.mapStatusCategory("", "Item picked up"));
        assertEquals("UNKNOWN",
                UspsDirectConnector.mapStatusCategory(null, null));
        assertEquals("UNKNOWN",
                UspsDirectConnector.mapStatusCategory("", ""));
    }

    @Test
    void buildUspsLocationHandlesFullData() {
        assertEquals("DENVER, CO 80202 US",
                UspsDirectConnector.buildUspsLocation("DENVER", "CO", "US", "80202"));
    }

    @Test
    void buildUspsLocationHandlesPartialData() {
        assertEquals("DENVER US",
                UspsDirectConnector.buildUspsLocation("DENVER", null, "US", null));
        assertEquals("DENVER, CO",
                UspsDirectConnector.buildUspsLocation("DENVER", "CO", null, null));
        assertEquals("80202 US",
                UspsDirectConnector.buildUspsLocation(null, null, "US", "80202"));
    }

    @Test
    void buildUspsLocationReturnsNullForEmptyInput() {
        assertNull(UspsDirectConnector.buildUspsLocation(null, null, null, null));
        assertNull(UspsDirectConnector.buildUspsLocation("", "", "", ""));
    }

    @Test
    void joinEventTimestampCombinesDateAndTime() {
        assertEquals(LocalDateTime.of(2026, 9, 15, 14, 30, 0),
                UspsDirectConnector.joinEventTimestamp("2026-09-15", "14:30:00"));
    }

    @Test
    void joinEventTimestampDefaultsMissingTimeToMidnight() {
        assertEquals(LocalDateTime.of(2026, 9, 15, 0, 0),
                UspsDirectConnector.joinEventTimestamp("2026-09-15", null));
        assertEquals(LocalDateTime.of(2026, 9, 15, 0, 0),
                UspsDirectConnector.joinEventTimestamp("2026-09-15", ""));
    }

    @Test
    void joinEventTimestampReturnsNullForBadDate() {
        assertNull(UspsDirectConnector.joinEventTimestamp(null, "14:30:00"));
        assertNull(UspsDirectConnector.joinEventTimestamp("BAD-DATE", "14:30:00"));
    }

    @Test
    void joinEventTimestampToleratesGarbledTime() {
        // Date OK but time garbled → date at midnight rather than dropping the event.
        assertEquals(LocalDateTime.of(2026, 9, 15, 0, 0),
                UspsDirectConnector.joinEventTimestamp("2026-09-15", "not-a-time"));
    }

    @Test
    void joinExpectedDeliveryDefaultsMissingTimeTo1700() {
        // Traditional USPS end-of-day when no time given.
        assertEquals(LocalDateTime.of(2026, 9, 17, 17, 0),
                UspsDirectConnector.joinExpectedDelivery("2026-09-17", null));
    }

    @Test
    void parseTrackingEventsHandlesEmptyAndMissing() {
        UspsDirectConnector c = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        assertTrue(c.parseTrackingEvents(null).isEmpty());
    }

    // ================================================================
    // 1-arg URL-only stub still works (back-compat)
    // ================================================================

    @Test
    void oneArgStubReturnsUrlOnly() {
        UspsDirectConnector c = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        TrackingResult r = c.trackShipment("9400111899223197428490");
        assertEquals("9400111899223197428490", r.trackingNumber());
        assertEquals("UNKNOWN", r.status());
        assertNotNull(r.trackingUrl());
        assertFalse(r.delivered());
        assertTrue(r.events().isEmpty());
    }

    // ================================================================
    // Fixture helper
    // ================================================================

    private static String loadFixture(String resourcePath) throws IOException {
        try (java.io.InputStream in = Objects.requireNonNull(
                UspsDirectTrackingTest.class.getClassLoader().getResourceAsStream(resourcePath),
                "fixture not on classpath: " + resourcePath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
