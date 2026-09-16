package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.CarrierConnector.AddressToValidate;
import com.multiship.backend.service.carriers.CarrierConnector.AddressValidationResult;
import com.multiship.backend.service.carriers.usps.UspsOAuthTokenCache;
import com.multiship.backend.service.carriers.usps.UspsPaymentAuthCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Address-validation regression tests for
 * {@link UspsDirectConnector#validateAddress}. PR-C.
 *
 * <p>Follows the seam-injection convention Agent 1 established for the
 * tracking path in PR-B: a package-visible
 * {@link UspsDirectConnector#executeAddressValidationGet(String, String)}
 * + {@link UspsDirectConnector#sleepBeforeAddressRetry(long)} let tests
 * feed canned USPS responses without a MockWebServer AND skip real
 * back-off so the 429-retry test stays sub-second.
 *
 * <p>Coverage matrix:
 * <ul>
 *   <li>Blank street → IAE.</li>
 *   <li>Blank city+state+ZIP (no locality hint) → IAE.</li>
 *   <li>Blank / null / {@code -local-} token → ISE with the /settings/system
 *       message (3 cases so a regression in any single guard shows up).</li>
 *   <li>200 clean address → EXACT + DPV=Y.</li>
 *   <li>200 with corrections[] → CORRECTED, corrections in warnings.</li>
 *   <li>200 with 2+ matches[] → CORRECTED + ambiguity warning.</li>
 *   <li>400 invalid address → soft NOT_FOUND result carrying the USPS
 *       error message; NO thrown exception.</li>
 *   <li>401 → ISE with the License Agreement remediation text.</li>
 *   <li>403 → ISE with the License Agreement remediation text.</li>
 *   <li>429 → retries with back-off, ultimately succeeds.</li>
 *   <li>429 exhausted → returns an ERROR result carrying HTTP 429.</li>
 *   <li>500 → returns an ERROR result carrying HTTP 500.</li>
 * </ul>
 */
class UspsDirectAddressValidationTest {

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
        // JdbcTemplate is unused by validateAddress — no CRID/MID lookup
        // for address validation — but the constructor requires it.
        jdbc = mock(JdbcTemplate.class);
    }

    private static AddressToValidate cleanAddress() {
        return new AddressToValidate(
                "Recipient",
                "ACME Widgets",
                "1600 Pennsylvania Ave NW",
                null,
                null,
                "Washington",
                "DC",
                "20500",
                "US");
    }

    /**
     * Package-private subclass that swaps the HTTP transport + back-off
     * sleep with fakes. {@link #sleepBeforeAddressRetry(long)} records
     * every attempted delay so tests can assert the retry loop actually
     * fired.
     */
    static class StubConnector extends UspsDirectConnector {
        final AtomicInteger callCount = new AtomicInteger(0);
        final List<Long> sleepDelays = new ArrayList<>();
        List<Object> responses;   // per-call: String OR RuntimeException

        StubConnector(CarrierProperties props, ObjectMapper mapper,
                      UspsOAuthTokenCache tokenCache,
                      UspsPaymentAuthCache paymentAuthCache,
                      JdbcTemplate jdbc) {
            super(props, mapper, tokenCache, paymentAuthCache, jdbc);
        }

        void queue(Object... items) {
            this.responses = new ArrayList<>(List.of(items));
        }

        @Override
        String executeAddressValidationGet(String url, String accessToken) {
            int idx = callCount.getAndIncrement();
            if (responses == null || idx >= responses.size()) {
                throw new AssertionError("Unexpected extra call at attempt " + (idx + 1)
                        + " (url=" + url + ")");
            }
            Object next = responses.get(idx);
            if (next instanceof RuntimeException re) throw re;
            return (String) next;
        }

        @Override
        void sleepBeforeAddressRetry(long millis) {
            sleepDelays.add(millis);
        }
    }

    private StubConnector newStub() {
        return new StubConnector(props, objectMapper, tokenCache, paymentAuthCache, jdbc);
    }

    /** Fabricate a RestClientResponseException — the class we catch in
     *  the retry loop. Body is UTF-8. */
    private static HttpClientErrorException clientError(int status, String body) {
        return HttpClientErrorException.create(
                HttpStatusCode.valueOf(status),
                HttpStatus.valueOf(status).getReasonPhrase(),
                HttpHeaders.EMPTY,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private static HttpServerErrorException serverError(int status, String body) {
        return HttpServerErrorException.create(
                HttpStatusCode.valueOf(status),
                HttpStatus.valueOf(status).getReasonPhrase(),
                HttpHeaders.EMPTY,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private static String loadFixture(String resourcePath) throws IOException {
        try (java.io.InputStream in = Objects.requireNonNull(
                UspsDirectAddressValidationTest.class.getClassLoader().getResourceAsStream(resourcePath),
                "fixture not on classpath: " + resourcePath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ================================================================
    // Boundary guards — blank / null / -local- token
    // ================================================================

    @Test
    void blankTokenThrowsWithNotConfiguredMessage() {
        StubConnector c = newStub();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> c.validateAddress(cleanAddress(), "", "SANDBOX"));
        assertTrue(ex.getMessage().contains("not configured"),
                "got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("USPS_PLATFORM_CLIENT_ID"),
                "message should name the setting keys; got: " + ex.getMessage());
    }

    @Test
    void nullTokenThrowsWithNotConfiguredMessage() {
        StubConnector c = newStub();
        assertThrows(IllegalStateException.class,
                () -> c.validateAddress(cleanAddress(), null, "SANDBOX"));
    }

    @Test
    void localFallbackTokenThrowsWithNotConfiguredMessage() {
        StubConnector c = newStub();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> c.validateAddress(cleanAddress(),
                        "usps-direct-local-ACCT-1", "SANDBOX"));
        assertTrue(ex.getMessage().contains("/settings/system"),
                "should point operator at /settings/system; got: " + ex.getMessage());
    }

    // ================================================================
    // Boundary guards — required address fields
    // ================================================================

    @Test
    void blankStreetAddressThrowsIAE() {
        StubConnector c = newStub();
        AddressToValidate noStreet = new AddressToValidate(
                "R", null, "", null, null, "Washington", "DC", "20500", "US");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> c.validateAddress(noStreet, "real-usps-oauth-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("street address"),
                "message must name the missing field; got: " + ex.getMessage());
    }

    @Test
    void nullAddressThrowsIAE() {
        StubConnector c = newStub();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> c.validateAddress(null, "real-usps-oauth-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("street address"),
                "null address still points at street; got: " + ex.getMessage());
    }

    @Test
    void blankCityStateAndZipThrowsIAE() {
        StubConnector c = newStub();
        AddressToValidate noLocality = new AddressToValidate(
                "R", null, "1600 Pennsylvania Ave NW", null, null,
                "", "", "", "US");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> c.validateAddress(noLocality, "real-usps-oauth-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("city+state or ZIP"),
                "message should name the alternatives; got: " + ex.getMessage());
    }

    @Test
    void cityStateOnly_no_zip_is_accepted() throws Exception {
        StubConnector c = newStub();
        c.queue(loadFixture("usps/v3/addresses/address_valid_clean.json"));
        AddressToValidate cityStateOnly = new AddressToValidate(
                "R", null, "1600 Pennsylvania Ave NW", null, null,
                "Washington", "DC", null, "US");
        AddressValidationResult r = c.validateAddress(cityStateOnly,
                "real-usps-oauth-token", "SANDBOX");
        assertTrue(r.valid(), "city+state alone should be enough; got: " + r);
    }

    @Test
    void zipOnly_no_city_state_is_accepted() throws Exception {
        StubConnector c = newStub();
        c.queue(loadFixture("usps/v3/addresses/address_valid_clean.json"));
        AddressToValidate zipOnly = new AddressToValidate(
                "R", null, "1600 Pennsylvania Ave NW", null, null,
                null, null, "20500", "US");
        AddressValidationResult r = c.validateAddress(zipOnly,
                "real-usps-oauth-token", "SANDBOX");
        assertTrue(r.valid(), "ZIP alone should be enough; got: " + r);
    }

    // ================================================================
    // Happy paths — 200 responses
    // ================================================================

    @Test
    void clean_200_response_returns_EXACT_with_DPV_Y() throws Exception {
        StubConnector c = newStub();
        c.queue(loadFixture("usps/v3/addresses/address_valid_clean.json"));
        AddressValidationResult r = c.validateAddress(cleanAddress(),
                "real-usps-oauth-token", "SANDBOX");

        assertTrue(r.valid(), "DPV=Y with no corrections should be valid");
        assertEquals("EXACT", r.matchLevel());
        assertTrue(r.message().contains("confirmed"),
                "operator-facing message should say confirmed; got: " + r.message());
        assertNotNull(r.rawResponse(), "raw payload should round-trip for debug");
        // No suggested address on EXACT — the caller doesn't need to
        // reconcile because nothing changed.
        assertNull(r.suggested(),
                "EXACT should not carry a suggested address (nothing changed)");
    }

    @Test
    void corrections_response_returns_CORRECTED_with_corrections_in_warnings() throws Exception {
        StubConnector c = newStub();
        c.queue(loadFixture("usps/v3/addresses/address_valid_with_corrections.json"));
        AddressValidationResult r = c.validateAddress(cleanAddress(),
                "real-usps-oauth-token", "SANDBOX");

        assertTrue(r.valid(),
                "DPV=Y with corrections should still be valid — USPS confirmed after standardizing");
        assertEquals("CORRECTED", r.matchLevel());
        assertNotNull(r.suggested(),
                "CORRECTED must carry a suggested address so the operator can review");
        assertEquals("350 5TH AVE", r.suggested().addressLine1());
        assertEquals("STE 6100", r.suggested().addressLine2());
        assertEquals("NEW YORK", r.suggested().city());
        assertEquals("NY", r.suggested().state());
        assertEquals("10118-0110", r.suggested().postalCode(),
                "ZIPPlus4 should combine into a single hyphenated postal code");
        // Corrections copied into warnings so the FE can render them
        // without re-parsing the raw JSON.
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("standardized")),
                "warnings should include the standardization note; got: " + r.warnings());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("city")),
                "warnings should include the city correction; got: " + r.warnings());
    }

    @Test
    void multiple_matches_appends_ambiguity_warning() throws Exception {
        StubConnector c = newStub();
        // Inline fixture: DPV=Y clean address BUT 2 match entries
        String json = """
                {
                  "address": {
                    "streetAddress": "1 MAIN ST",
                    "city": "SPRINGFIELD",
                    "state": "IL",
                    "ZIPCode": "62701"
                  },
                  "additionalInfo": { "DPVConfirmation": "Y" },
                  "corrections": [],
                  "matches": [
                    { "code": "31", "text": "First candidate" },
                    { "code": "31", "text": "Second candidate" }
                  ]
                }
                """;
        c.queue(json);
        AddressValidationResult r = c.validateAddress(cleanAddress(),
                "real-usps-oauth-token", "SANDBOX");
        assertTrue(r.valid());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("2 possible matches")),
                "warnings should note the ambiguity; got: " + r.warnings());
    }

    @Test
    void DPV_D_missing_secondary_returns_CORRECTED_with_advisory_warning() {
        StubConnector c = newStub();
        String json = """
                {
                  "address": {
                    "streetAddress": "1 MAIN ST",
                    "city": "SPRINGFIELD",
                    "state": "IL",
                    "ZIPCode": "62701"
                  },
                  "additionalInfo": { "DPVConfirmation": "D" }
                }
                """;
        c.queue(json);
        AddressValidationResult r = c.validateAddress(cleanAddress(),
                "real-usps-oauth-token", "SANDBOX");
        assertTrue(r.valid(), "DPV=D is still deliverable per USPS");
        assertEquals("CORRECTED", r.matchLevel());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("secondary unit")),
                "should warn about the missing apt/suite; got: " + r.warnings());
    }

    @Test
    void DPV_N_returns_NOT_FOUND() {
        StubConnector c = newStub();
        String json = """
                {
                  "address": {},
                  "additionalInfo": { "DPVConfirmation": "N" }
                }
                """;
        c.queue(json);
        AddressValidationResult r = c.validateAddress(cleanAddress(),
                "real-usps-oauth-token", "SANDBOX");
        assertFalse(r.valid());
        assertEquals("NOT_FOUND", r.matchLevel());
    }

    // ================================================================
    // 400 — soft failure (address invalid, not a system error)
    // ================================================================

    @Test
    void http_400_invalid_address_returns_soft_result_no_exception() throws Exception {
        StubConnector c = newStub();
        String errorBody = loadFixture("usps/v3/addresses/address_invalid_400.json");
        c.queue(clientError(400, errorBody));
        // Deliberately NOT wrapped in assertThrows — the whole point
        // of the 400 soft-failure design is that operators see the
        // reason inline in the panel, not a stack trace.
        AddressValidationResult r = c.validateAddress(cleanAddress(),
                "real-usps-oauth-token", "SANDBOX");
        assertFalse(r.valid());
        assertEquals("NOT_FOUND", r.matchLevel());
        assertTrue(r.message().contains("Address Not Found")
                        || r.message().contains("verify"),
                "should surface the USPS reason; got: " + r.message());
        assertNotNull(r.rawResponse(), "raw error body should round-trip for debug");
    }

    // ================================================================
    // 401 / 403 — License Agreement guard
    // ================================================================

    @Test
    void http_401_throws_ISE_with_license_agreement_remediation() throws Exception {
        StubConnector c = newStub();
        String errorBody = loadFixture("usps/v3/addresses/address_license_required_401.json");
        c.queue(clientError(401, errorBody));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> c.validateAddress(cleanAddress(),
                        "real-usps-oauth-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("License Agreement"),
                "should name the license requirement; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("developers.usps.com"),
                "should point to the sign-up URL; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("SANDBOX"),
                "should mention SANDBOX as the interim workaround; got: " + ex.getMessage());
    }

    @Test
    void http_403_throws_ISE_with_license_agreement_remediation() {
        StubConnector c = newStub();
        c.queue(clientError(403, "{\"error\":{\"message\":\"Forbidden\"}}"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> c.validateAddress(cleanAddress(),
                        "real-usps-oauth-token", "PRODUCTION"));
        assertTrue(ex.getMessage().contains("License Agreement"),
                "403 should reach the same guard as 401; got: " + ex.getMessage());
    }

    // ================================================================
    // 429 — exponential back-off + retry
    // ================================================================

    @Test
    void http_429_retries_then_succeeds() throws Exception {
        StubConnector c = newStub();
        String body = loadFixture("usps/v3/addresses/address_valid_clean.json");
        // Two 429s then a 200 on attempt 3 — succeeds within the retry
        // budget (3 additional attempts).
        c.queue(
                clientError(429, "{\"error\":{\"message\":\"rate limit exceeded\"}}"),
                clientError(429, "{\"error\":{\"message\":\"rate limit exceeded\"}}"),
                body);
        AddressValidationResult r = c.validateAddress(cleanAddress(),
                "real-usps-oauth-token", "SANDBOX");
        assertTrue(r.valid(), "third attempt returns 200; must succeed");
        assertEquals(3, c.callCount.get(),
                "should have hit USPS three times before giving up");
        assertEquals(2, c.sleepDelays.size(),
                "should have slept between the two 429s (not after the 200)");
        // First delay ~2s base, second ~4s base; both include jitter.
        assertTrue(c.sleepDelays.get(0) >= 2_000 && c.sleepDelays.get(0) < 2_600,
                "first back-off ≈ 2s + 0..500ms jitter; got: " + c.sleepDelays.get(0));
        assertTrue(c.sleepDelays.get(1) >= 4_000 && c.sleepDelays.get(1) < 4_600,
                "second back-off ≈ 4s + 0..500ms jitter; got: " + c.sleepDelays.get(1));
    }

    @Test
    void http_429_exhausted_returns_ERROR_result() {
        StubConnector c = newStub();
        // 4 429s in a row = initial + 3 retries all fail. The 4th
        // throw escapes the retry loop as an HTTP failure and turns
        // into an ERROR result.
        c.queue(
                clientError(429, "rate limit"),
                clientError(429, "rate limit"),
                clientError(429, "rate limit"),
                clientError(429, "rate limit"));
        AddressValidationResult r = c.validateAddress(cleanAddress(),
                "real-usps-oauth-token", "SANDBOX");
        assertFalse(r.valid());
        assertEquals("ERROR", r.matchLevel());
        assertTrue(r.message().contains("429"),
                "operator should see the status code; got: " + r.message());
        assertEquals(4, c.callCount.get(),
                "should have exhausted all 4 attempts (1 initial + 3 retries)");
        assertEquals(3, c.sleepDelays.size(),
                "should have slept 3 times, one between each failed retry");
    }

    // ================================================================
    // 5xx — wrapped ERROR result
    // ================================================================

    @Test
    void http_500_returns_ERROR_result_with_actionable_text() {
        StubConnector c = newStub();
        c.queue(serverError(500, "{\"error\":{\"message\":\"upstream failure\"}}"));
        AddressValidationResult r = c.validateAddress(cleanAddress(),
                "real-usps-oauth-token", "SANDBOX");
        assertFalse(r.valid());
        assertEquals("ERROR", r.matchLevel());
        assertTrue(r.message().contains("500"),
                "should surface the status code; got: " + r.message());
        assertTrue(r.message().contains("retry") || r.message().contains("responsive")
                        || r.message().contains("manually"),
                "should include remediation; got: " + r.message());
        assertNotNull(r.rawResponse(), "raw body should round-trip for debug");
    }

    // ================================================================
    // URL builder
    // ================================================================

    @Test
    void buildAddressValidationUrl_encodes_all_query_params() {
        UspsDirectConnector c = newStub();
        AddressToValidate addr = new AddressToValidate(
                "R", null,
                "1600 Pennsylvania Ave NW", "Ste 200", null,
                "Washington", "DC", "20500-0005", "US");
        String url = c.buildAddressValidationUrl(addr, "SANDBOX");
        assertTrue(url.startsWith(UspsOAuthTokenCache.SANDBOX_HOST + "/addresses/v3/address?"),
                "should hit the SANDBOX host + addresses path; got: " + url);
        assertTrue(url.contains("streetAddress=1600+Pennsylvania+Ave+NW"),
                "street should be URL-encoded (spaces → +); got: " + url);
        assertTrue(url.contains("secondaryAddress=Ste+200"), "got: " + url);
        assertTrue(url.contains("city=Washington"), "got: " + url);
        assertTrue(url.contains("state=DC"), "got: " + url);
        assertTrue(url.contains("ZIPCode=20500"),
                "ZIP+4 should split into ZIPCode + ZIPPlus4; got: " + url);
        assertTrue(url.contains("ZIPPlus4=0005"),
                "the +4 half must be sent separately; got: " + url);
    }

    @Test
    void buildAddressValidationUrl_handles_9digit_zip_without_dash() {
        UspsDirectConnector c = newStub();
        AddressToValidate addr = new AddressToValidate(
                "R", null,
                "1 Main St", null, null,
                "Springfield", "IL", "627010001", "US");
        String url = c.buildAddressValidationUrl(addr, "SANDBOX");
        assertTrue(url.contains("ZIPCode=62701"), "got: " + url);
        assertTrue(url.contains("ZIPPlus4=0001"), "got: " + url);
    }

    @Test
    void buildAddressValidationUrl_skips_blank_fields() {
        UspsDirectConnector c = newStub();
        AddressToValidate addr = new AddressToValidate(
                null, null,
                "1 Main St", null, null,
                "Springfield", "IL", "62701", "US");
        String url = c.buildAddressValidationUrl(addr, "SANDBOX");
        assertFalse(url.contains("secondaryAddress="),
                "blank secondary should not appear as empty param; got: " + url);
    }

    @Test
    void buildAddressValidationUrl_routes_to_prod_host_for_production_env() {
        UspsDirectConnector c = newStub();
        String url = c.buildAddressValidationUrl(cleanAddress(), "PRODUCTION");
        assertTrue(url.startsWith(UspsOAuthTokenCache.PROD_HOST),
                "PRODUCTION env should route to prod host; got: " + url);
    }
}
