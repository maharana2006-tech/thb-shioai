package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.CarrierConnector.RateOption;
import com.multiship.backend.service.carriers.usps.UspsOAuthTokenCache;
import com.multiship.backend.service.carriers.usps.UspsPaymentAuthCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Boundary-guard + rate-parser regression tests for
 * {@link UspsDirectConnector#getRates}.
 *
 * <p>Mirrors the {@link RecipientCountryGuardTest} shape for the four
 * other carriers so a future audit can see the whole matrix in one
 * place. Every entry point must fail loudly BEFORE calling USPS when
 * the request is incomplete:
 * <ul>
 *   <li>Blank / null / {@code -local-} token → {@link IllegalStateException}
 *       naming the {@code /settings/system} keys the operator needs to
 *       set.</li>
 *   <li>Missing recipient country → {@link IllegalArgumentException}
 *       explaining the silent US-domestic fallback risk.</li>
 *   <li>Missing tenant CRID / MID / EPS account → {@link IllegalArgumentException}
 *       pointing at {@code /settings/carriers}.</li>
 * </ul>
 *
 * <p>Happy-path parse verifies the fixture JSON becomes the expected
 * {@link RateOption} list.
 */
class UspsDirectRateShopTest {

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
        // Real JdbcTemplate would need a live DB — stub it out. Individual
        // tests override the query() answer to seed / miss the tenant row.
        jdbc = mock(JdbcTemplate.class);
    }

    private static ShipmentRequestDTO minimal(String recipientCountry) {
        return ShipmentRequestDTO.builder()
                .carrierCode("USPS")
                .accountNumber("ACCT-7788")
                .serviceType("USPS_GROUND_ADVANTAGE")
                .packageType("USPS_PACKAGE")
                .weight(new BigDecimal("1"))
                .weightUnit("LB")
                .shipperName("Sender")
                .shipperAddressLine1("1 A St")
                .shipperCity("Denver")
                .shipperState("CO")
                .shipperPostalCode("80202")
                .shipperCountryCode("US")
                .recipientName("Recipient")
                .recipientAddressLine1("2 B St")
                .recipientCity("NYC")
                .recipientState("NY")
                .recipientPostalCode("10001")
                .recipientCountryCode(recipientCountry)
                .referenceNumber("PO-9999")
                .build();
    }

    /** Wire the JdbcTemplate stub to return a fully-populated tenant row. */
    private void seedTenant(String accountNumber, String crid, String mid) {
        doAnswer(invocation -> {
            org.springframework.jdbc.core.ResultSetExtractor<?> extractor =
                    invocation.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getString("acct")).thenReturn(accountNumber);
            when(rs.getString("crid")).thenReturn(crid);
            when(rs.getString("mid")).thenReturn(mid);
            return extractor.extractData(rs);
        }).when(jdbc).query(anyString(),
                any(org.springframework.jdbc.core.ResultSetExtractor.class),
                any(Object[].class));
    }

    /** JdbcTemplate returns null — no tenant row found. */
    private void tenantNotFound() {
        doAnswer(invocation -> {
            org.springframework.jdbc.core.ResultSetExtractor<?> extractor =
                    invocation.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.next()).thenReturn(false);
            return extractor.extractData(rs);
        }).when(jdbc).query(anyString(),
                any(org.springframework.jdbc.core.ResultSetExtractor.class),
                any(Object[].class));
    }

    // ================================================================
    // -local- / blank / null token guard
    // ================================================================

    @Test
    void blankTokenThrowsWithNotConfiguredMessage() {
        UspsDirectConnector connector = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> connector.getRates(minimal("US"), "", "SANDBOX"));
        assertTrue(ex.getMessage().contains("not configured"),
                "message should say 'not configured'; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("USPS_PLATFORM_CLIENT_ID"),
                "message should name the system-setting keys; got: " + ex.getMessage());
    }

    @Test
    void nullTokenThrowsWithNotConfiguredMessage() {
        UspsDirectConnector connector = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> connector.getRates(minimal("US"), null, "SANDBOX"));
        assertTrue(ex.getMessage().contains("not configured"),
                "got: " + ex.getMessage());
    }

    @Test
    void localFallbackTokenThrowsWithNotConfiguredMessage() {
        UspsDirectConnector connector = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> connector.getRates(minimal("US"),
                        "usps-direct-local-ACCT-7788", "SANDBOX"));
        assertTrue(ex.getMessage().contains("USPS_PLATFORM_CLIENT_ID"),
                "message should point operator at /settings/system; got: " + ex.getMessage());
    }

    // ================================================================
    // Recipient country guard
    // ================================================================

    @Test
    void blankRecipientCountryThrows() {
        UspsDirectConnector connector = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.getRates(minimal(""),
                        "real-usps-oauth-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("recipient country"),
                "got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("PO-9999"),
                "message must name the order for log-correlation; got: " + ex.getMessage());
    }

    @Test
    void nullRecipientCountryThrows() {
        UspsDirectConnector connector = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        assertThrows(IllegalArgumentException.class,
                () -> connector.getRates(minimal(null),
                        "real-usps-oauth-token", "SANDBOX"));
    }

    // ================================================================
    // Tenant identifier guards (CRID / MID / account)
    // ================================================================

    @Test
    void missingTenantAllThreeThrowsAndNamesFields() {
        tenantNotFound();
        UspsDirectConnector connector = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.getRates(minimal("US"),
                        "real-usps-oauth-token", "SANDBOX"));
        String msg = ex.getMessage();
        assertTrue(msg.contains("crid"), "message should name crid; got: " + msg);
        assertTrue(msg.contains("mid"), "message should name mid; got: " + msg);
        assertTrue(msg.contains("account"), "message should name account; got: " + msg);
        assertTrue(msg.contains("/settings/carriers"),
                "message should point operator at /settings/carriers; got: " + msg);
        assertTrue(msg.contains("PO-9999"),
                "message must name the order; got: " + msg);
    }

    @Test
    void missingCridOnlyThrowsAndNamesCrid() {
        seedTenant("ACCT-7788", "", "MID-1");
        UspsDirectConnector connector = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.getRates(minimal("US"),
                        "real-usps-oauth-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("crid"),
                "message should call out the missing crid; got: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("account/"),
                "message must not conflate with the account field; got: " + ex.getMessage());
    }

    @Test
    void missingMidOnlyThrowsAndNamesMid() {
        seedTenant("ACCT-7788", "CRID-1", "");
        UspsDirectConnector connector = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.getRates(minimal("US"),
                        "real-usps-oauth-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("mid"),
                "got: " + ex.getMessage());
    }

    @Test
    void missingAccountOnlyThrowsAndNamesAccount() {
        seedTenant("", "CRID-1", "MID-1");
        UspsDirectConnector connector = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.getRates(minimal("US"),
                        "real-usps-oauth-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("account"),
                "got: " + ex.getMessage());
    }

    @Test
    void blankRequestAccountNumberThrowsBeforeLookup() {
        UspsDirectConnector connector = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        ShipmentRequestDTO r = minimal("US");
        r.setAccountNumber("");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.getRates(r, "real-usps-oauth-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("shipper account number"),
                "got: " + ex.getMessage());
    }

    // ================================================================
    // Happy-path parser — fixture JSON → RateOption list
    // ================================================================

    @Test
    void parseRateResponse_ground_advantage_fixture_yields_three_options() throws Exception {
        String fixture = loadFixture("usps/v3/rate_response_ground_advantage.json");
        UspsDirectConnector connector = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);

        List<RateOption> options = connector.parseRateResponse(fixture);
        assertEquals(3, options.size(),
                "fixture has 3 mail classes → 3 RateOptions");

        RateOption ground = options.stream()
                .filter(o -> "USPS_GROUND_ADVANTAGE".equals(o.serviceCode()))
                .findFirst().orElseThrow();
        // compareTo — parsed decimals may differ in scale from literals.
        assertEquals(0, ground.totalAmount().compareTo(new BigDecimal("8.85")));
        assertEquals("USD", ground.currency());
        assertEquals("USPS", ground.carrierCode());
        assertNotNull(ground.serviceName());

        RateOption priority = options.stream()
                .filter(o -> "PRIORITY_MAIL".equals(o.serviceCode()))
                .findFirst().orElseThrow();
        assertEquals(0, priority.totalAmount().compareTo(new BigDecimal("12.65")));

        RateOption express = options.stream()
                .filter(o -> "PRIORITY_MAIL_EXPRESS".equals(o.serviceCode()))
                .findFirst().orElseThrow();
        assertEquals(0, express.totalAmount().compareTo(new BigDecimal("32.15")));
    }

    @Test
    void parseRateResponse_empty_body_yields_empty_list() throws Exception {
        UspsDirectConnector connector = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        assertTrue(connector.parseRateResponse("{}").isEmpty(),
                "empty response should not blow up — return empty list");
        assertTrue(connector.parseRateResponse(null).isEmpty(),
                "null response should not blow up — return empty list");
    }

    @Test
    void parseRateResponse_missing_mailClass_skips_entry() throws Exception {
        String fixture = "{\"rateOptions\":[{\"totalPrice\":10,\"rates\":[{\"price\":10}]}]}";
        UspsDirectConnector connector = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        // Missing mailClass = we can't identify the service, drop it
        // rather than emit a nameless RateOption.
        assertTrue(connector.parseRateResponse(fixture).isEmpty());
    }

    // ================================================================
    // buildRateRequestBody wire-shape
    // ================================================================

    @Test
    void buildRateRequestBody_carries_tenant_identifiers_and_zip_codes() {
        UspsDirectConnector connector = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        ShipmentRequestDTO req = minimal("US");
        UspsDirectConnector.TenantIdentifiers tenant = new UspsDirectConnector
                .TenantIdentifiers("ACCT-7788", "CRID-99", "MID-88");
        java.util.Map<String, Object> body = connector.buildRateRequestBody(
                req, req.effectivePackages().get(0), tenant);

        assertEquals("80202", body.get("originZIPCode"));
        assertEquals("10001", body.get("destinationZIPCode"));
        assertEquals("MACHINABLE", body.get("processingCategory"));
        assertEquals("SP", body.get("rateIndicator"));
        assertEquals("NONE", body.get("destinationEntryFacilityType"));
        assertEquals("COMMERCIAL", body.get("priceType"));
        assertEquals("ACCT-7788", body.get("accountNumber"));
        assertEquals("CRID-99", body.get("CRID"));
        assertEquals("MID-88", body.get("MID"));
    }

    // ================================================================
    // Fixture helper
    // ================================================================

    private static String loadFixture(String resourcePath) throws IOException {
        try (java.io.InputStream in = Objects.requireNonNull(
                UspsDirectRateShopTest.class.getClassLoader().getResourceAsStream(resourcePath),
                "fixture not on classpath: " + resourcePath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
