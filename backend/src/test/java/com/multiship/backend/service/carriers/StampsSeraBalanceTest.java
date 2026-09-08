package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.CarrierConnector.BalanceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused unit tests for the SERA {@code GET /sera/v1/balance} branch.
 *
 * <p>Behaviour under test:
 * <ol>
 *   <li>Response parsing — {@code amount_available},
 *       {@code max_balance_amount_allowed}, {@code currency} land in
 *       {@link BalanceResult} with currency uppercased to ISO-4217.</li>
 *   <li>Missing {@code amount_available} → ERROR, NOT a bogus zero (a
 *       silent zero would look like an empty account and mislead the
 *       operator).</li>
 *   <li>Flavor dispatch — SWSIM-flavored callers get NOT_SUPPORTED with
 *       an actionable message; SERA-flavored callers hit the endpoint.</li>
 *   <li>Fallback tokens → NOT_SUPPORTED — same sibling-parity guard the
 *       other SERA branches use.</li>
 * </ol>
 */
class StampsSeraBalanceTest {

    private StampsConnector connector;

    @BeforeEach
    void setUp() {
        CarrierProperties props = new CarrierProperties();
        CarrierProperties.Stamps s = props.getStamps();
        s.setApiFlavor("SERA");
        s.setSeraApiBaseUrl("http://localhost:1/sera");
        s.setSeraSandboxApiBaseUrl("http://localhost:1/sera-sandbox");
        connector = new StampsConnector(props, new ObjectMapper());
    }

    // ===== response parsing =====

    @Test
    void parseSeraBalanceResponse_happyPath() {
        String json = """
                {
                  "amount_available": 42.50,
                  "max_balance_amount_allowed": 500.00,
                  "currency": "usd"
                }
                """;
        BalanceResult r = connector.parseSeraBalanceResponse(json);
        assertEquals("OK", r.status());
        // Compare via compareTo to avoid trailing-zero (scale) mismatch —
        // Jackson decodes 42.50 → BigDecimal("42.5"), which is still
        // numerically equal but has a different scale than the literal.
        assertEquals(0, r.amountAvailable().compareTo(new BigDecimal("42.5")));
        assertEquals(0, r.maxBalance().compareTo(new BigDecimal("500.0")));
        // Currency uppercased to ISO-4217 for downstream consistency —
        // SERA emits lowercase "usd" but the rest of the system speaks "USD".
        assertEquals("USD", r.currency());
        assertTrue(r.message().contains("42.5"), "message must surface the amount for operator UI");
    }

    @Test
    void parseSeraBalanceResponse_missingAmount_surfacesError() {
        // A response without amount_available can't produce an honest
        // BalanceResult — silent zero would mislead the operator into
        // topping up a prepaid account that isn't actually empty.
        BalanceResult r = connector.parseSeraBalanceResponse(
                "{\"currency\": \"usd\"}");
        assertEquals("ERROR", r.status());
        assertNull(r.amountAvailable());
        assertTrue(r.message().toLowerCase().contains("amount_available"),
                "error message must call out the missing field for debuggability");
    }

    @Test
    void parseSeraBalanceResponse_stringAmount_parses() {
        // Some gateway proxies re-emit SERA's response with money as
        // strings ("42.50"). Tolerate that form.
        BalanceResult r = connector.parseSeraBalanceResponse(
                "{\"amount_available\": \"42.50\", \"currency\": \"USD\"}");
        assertEquals("OK", r.status());
        assertEquals(new BigDecimal("42.50"), r.amountAvailable());
    }

    @Test
    void parseSeraBalanceResponse_emptyBody_surfacesError() {
        BalanceResult r = connector.parseSeraBalanceResponse("");
        assertEquals("ERROR", r.status());
        assertNull(r.amountAvailable());
    }

    @Test
    void parseSeraBalanceResponse_malformedJson_surfacesError() {
        BalanceResult r = connector.parseSeraBalanceResponse("not-json-at-all");
        assertEquals("ERROR", r.status());
        assertTrue(r.message().toLowerCase().contains("json") || r.message().toLowerCase().contains("malformed"),
                "must surface the parse failure; got: " + r.message());
    }

    // ===== dispatch =====

    @Test
    void getAccountBalance_swsimFlavor_returnsNotSupported() {
        CarrierProperties props = new CarrierProperties();
        props.getStamps().setApiFlavor("SWSIM");
        StampsConnector swsimConnector = new StampsConnector(props, new ObjectMapper());
        BalanceResult r = swsimConnector.getAccountBalance("real-token", "PRODUCTION");
        assertEquals("NOT_SUPPORTED", r.status());
        assertTrue(r.message().toLowerCase().contains("swsim") || r.message().toLowerCase().contains("dashboard"),
                "SWSIM NOT_SUPPORTED must guide operator to dashboard or SERA flip; got: " + r.message());
    }

    @Test
    void getAccountBalance_localFallbackToken_returnsNotSupported() {
        BalanceResult r = connector.getAccountBalanceSera("stamps-local-abc", "PRODUCTION");
        assertEquals("NOT_SUPPORTED", r.status());
        assertTrue(r.message().toLowerCase().contains("fallback"));
    }

    @Test
    void getAccountBalance_seraFlavor_unreachableHost_returnsError() {
        // localhost:1 refuses → the RestClient throws, connector logs +
        // surfaces ERROR with the underlying reason. No fallback to zero.
        BalanceResult r = connector.getAccountBalance("real-sera-token", "PRODUCTION");
        assertEquals("ERROR", r.status());
        assertNull(r.amountAvailable());
    }

    @Test
    void defaultConnectorImpl_returnsNotSupported_soCarriersDontRegress() {
        // Every non-Stamps carrier inherits the CarrierConnector default,
        // which must return NOT_SUPPORTED so no accidental zero-balance
        // report is emitted from FedEx / UPS / DHL.
        CarrierConnector stub = new CarrierConnector() {
            public String getCarrierCode() { return "STUB"; }
            public String getCarrierName() { return "Stub"; }
            public com.multiship.backend.service.carriers.CarrierConnector.ServiceAvailability
                    listServices(String o, String t, String e) { return null; }
            public com.multiship.backend.service.carriers.CarrierConnector.PackageAvailability
                    listPackages(String o, String t, String e) { return null; }
            public com.multiship.backend.service.carriers.CarrierConnector.CarrierConnectionResult
                    connect(String c, String s, String a) { return null; }
            public String getAccessToken(String c, String s) { return null; }
            public com.multiship.backend.service.carriers.CarrierConnector.ShipmentResult
                    createShipment(com.multiship.backend.dto.ShipmentRequestDTO r, String t, String e) { return null; }
            public boolean validateCredentials(String c, String s) { return false; }
            public com.multiship.backend.service.carriers.CarrierConnector.TrackingResult
                    trackShipment(String t) { return null; }
            public com.multiship.backend.service.carriers.CarrierConnector.CarrierConfiguration
                    getConfiguration() { return null; }
        };
        BalanceResult r = stub.getAccountBalance("token", "PRODUCTION");
        assertEquals("NOT_SUPPORTED", r.status());
        assertEquals("STUB", r.carrierCode());
    }
}
