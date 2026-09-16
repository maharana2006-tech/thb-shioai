package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.CarrierConnector.ShipmentResult;
import com.multiship.backend.service.carriers.usps.UspsCustomsFormBuilder;
import com.multiship.backend.service.carriers.usps.UspsOAuthTokenCache;
import com.multiship.backend.service.carriers.usps.UspsPaymentAuthCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
 * Payload-shape + boundary-guard tests for the intl branch of
 * {@link UspsDirectConnector#createShipment}.
 *
 * <p>Mirrors {@link UspsDirectConnectorPayloadTest} for the domestic
 * path. Every guard is exercised in isolation, plus one happy-path
 * that pins the wire body's key slots (senderInfo.CRID/MID,
 * paymentInfo.accountNumber, top-level destinationCountryCode,
 * customsForm.commodities[0].hsTariffNumber).
 *
 * <p><b>REGULATORY_REFERENCE.</b> HS-6 mandate assertions are the
 * connector-boundary layer of the 2025-09-01 USPS International HS-6
 * Tariff Mandate enforcement. Tests here pin the exact error message
 * so a future refactor that changes the wording doesn't silently
 * relax the enforcement.
 */
class UspsDirectIntlLabelTest {

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
        paymentAuthCache = mock(UspsPaymentAuthCache.class);
        jdbc = mock(JdbcTemplate.class);
    }

    private UspsDirectConnector newConnector() {
        UspsDirectConnector c = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc);
        c.setCustomsFormBuilder(new UspsCustomsFormBuilder());
        return c;
    }

    /** Wire the JdbcTemplate stub + payment-auth mock for happy-path. */
    private void seedTenantAndPaymentAuth() {
        doAnswer(invocation -> {
            org.springframework.jdbc.core.ResultSetExtractor<?> extractor =
                    invocation.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getString("acct")).thenReturn("EPS-INTL-1");
            when(rs.getString("crid")).thenReturn("CRID-INTL-1");
            when(rs.getString("mid")).thenReturn("MID-INTL-1");
            return extractor.extractData(rs);
        }).when(jdbc).query(anyString(),
                any(org.springframework.jdbc.core.ResultSetExtractor.class),
                any(Object[].class));
        when(paymentAuthCache.getToken(anyString(), anyString(), anyString(),
                        anyString(), anyString()))
                .thenReturn(java.util.Optional.of("pay-auth-token-42"));
    }

    private static ShipmentRequestDTO intlRequest(String recipientCountry,
                                                    List<CustomsCommodityDTO> commodities) {
        IntlShipmentBlockDTO intl = IntlShipmentBlockDTO.builder()
                .reasonForExport("SALE")
                .customsCurrency("USD")
                .incoterms("DAP")
                .commodities(commodities)
                .build();
        return ShipmentRequestDTO.builder()
                .carrierCode("USPS")
                .accountNumber("ACCT-7788")
                .serviceType("PRIORITY_MAIL_INTERNATIONAL")
                .packageType("USPS_PACKAGE")
                .weight(new BigDecimal("2"))
                .weightUnit("LB")
                .length(new BigDecimal("10"))
                .width(new BigDecimal("6"))
                .height(new BigDecimal("4"))
                .dimUnit("IN")
                .shipperName("ACME Warehouse")
                .shipperCompany("ACME Inc")
                .shipperAddressLine1("1 Warehouse Rd")
                .shipperCity("Denver").shipperState("CO")
                .shipperPostalCode("80202").shipperCountryCode("US")
                .recipientName("Alice Recipient")
                .recipientAddressLine1("10 Downing St")
                .recipientCity("London")
                .recipientPostalCode("SW1A 2AA")
                .recipientCountryCode(recipientCountry)
                .referenceNumber("PO-INTL-42")
                .intl(intl)
                .build();
    }

    private static CustomsCommodityDTO goodCommodity() {
        return CustomsCommodityDTO.builder()
                .description("Cotton t-shirt")
                .quantity(2)
                .unitValue(new BigDecimal("15.00"))
                .unitWeight(new BigDecimal("0.5"))
                .hsCode("6109.10")
                .countryOfOrigin("US")
                .build();
    }

    // ================================================================
    // Boundary guards — customs block + HS + country-of-origin
    // ================================================================

    @Test
    void noCustomsBlockThrows() {
        seedTenantAndPaymentAuth();
        UspsDirectConnector connector = newConnector();
        // Build an intl request but strip the intl block.
        ShipmentRequestDTO req = intlRequest("GB", List.of(goodCommodity()));
        req.setIntl(null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.createShipment(req, "real-token", "SANDBOX"));
        String msg = ex.getMessage();
        assertTrue(msg.contains("customs block"),
                "message must name the missing block; got: " + msg);
        assertTrue(msg.contains("PO-INTL-42"),
                "message must name the order; got: " + msg);
    }

    @Test
    void emptyCommoditiesListThrows() {
        seedTenantAndPaymentAuth();
        UspsDirectConnector connector = newConnector();
        ShipmentRequestDTO req = intlRequest("GB", new ArrayList<>());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.createShipment(req, "real-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("at least one commodity"),
                "got: " + ex.getMessage());
    }

    @Test
    void missingHsCodeThrowsWithRegulatoryPointer() {
        seedTenantAndPaymentAuth();
        UspsDirectConnector connector = newConnector();
        CustomsCommodityDTO bad = CustomsCommodityDTO.builder()
                .description("No-HS commodity")
                .quantity(1)
                .unitValue(new BigDecimal("20"))
                .countryOfOrigin("US")
                .build();  // hsCode intentionally null
        ShipmentRequestDTO req = intlRequest("GB", List.of(goodCommodity(), bad));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.createShipment(req, "real-token", "SANDBOX"));
        String msg = ex.getMessage();
        assertTrue(msg.contains("6-digit HS tariff number"),
                "message must name the requirement; got: " + msg);
        assertTrue(msg.contains("2025-09-01"),
                "message must cite the mandate date; got: " + msg);
        assertTrue(msg.contains("Commodity 2"),
                "message must name the 1-based commodity index; got: " + msg);
        assertTrue(msg.contains("No-HS commodity"),
                "message must echo the description; got: " + msg);
    }

    @Test
    void shortHsCodeThrows() {
        seedTenantAndPaymentAuth();
        UspsDirectConnector connector = newConnector();
        CustomsCommodityDTO shortHs = CustomsCommodityDTO.builder()
                .description("Short-HS")
                .quantity(1)
                .unitValue(new BigDecimal("20"))
                .countryOfOrigin("US")
                .hsCode("610")  // 3 digits only
                .build();
        ShipmentRequestDTO req = intlRequest("GB", List.of(shortHs));
        assertThrows(IllegalArgumentException.class,
                () -> connector.createShipment(req, "real-token", "SANDBOX"));
    }

    @Test
    void hsCodeWithDashesNormalisesTo6DigitsAndPasses() {
        // "6109-10" normalises to "610910" = 6 digits — should pass the
        // boundary guard. Then falls through to the wire-call layer,
        // which we short-circuit via the seam.
        seedTenantAndPaymentAuth();
        AtomicReference<Map<String, Object>> capturedBody = new AtomicReference<>();
        UspsDirectConnector connector = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc) {
            @Override
            String executeIntlLabelPost(String url, Map<String, Object> body, String accessToken,
                                         Map<String, String> extraHeaders) {
                capturedBody.set(body);
                // Return an empty valid response so parseIntlLabelResponse
                // doesn't throw.
                return "{\"labelMetadata\":{\"trackingNumber\":\"CP1\",\"postage\":10}}";
            }
        };
        connector.setCustomsFormBuilder(new UspsCustomsFormBuilder());
        CustomsCommodityDTO dashed = CustomsCommodityDTO.builder()
                .description("Dashed-HS")
                .quantity(1)
                .unitValue(new BigDecimal("20"))
                .countryOfOrigin("US")
                .hsCode("6109-10")
                .build();
        ShipmentRequestDTO req = intlRequest("GB", List.of(dashed));
        ShipmentResult result = connector.createShipment(req, "real-token", "SANDBOX");
        assertNotNull(result);
        assertEquals("CP1", result.trackingNumber());
        // Wire body: HS normalised to 610910 in commodity[0].
        @SuppressWarnings("unchecked")
        Map<String, Object> customsForm = (Map<String, Object>) capturedBody.get().get("customsForm");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commodities = (List<Map<String, Object>>) customsForm.get("commodities");
        assertEquals("610910", commodities.get(0).get("hsTariffNumber"),
                "HS code must be dash-stripped on the wire");
    }

    @Test
    void missingCountryOfOriginThrows() {
        seedTenantAndPaymentAuth();
        UspsDirectConnector connector = newConnector();
        CustomsCommodityDTO noCoo = CustomsCommodityDTO.builder()
                .description("No-COO")
                .quantity(1)
                .unitValue(new BigDecimal("20"))
                .hsCode("610910")
                .build();  // countryOfOrigin intentionally null
        ShipmentRequestDTO req = intlRequest("GB", List.of(noCoo));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> connector.createShipment(req, "real-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("ISO alpha-2 country of origin"),
                "got: " + ex.getMessage());
    }

    @Test
    void nonIsoCountryOfOriginThrows() {
        seedTenantAndPaymentAuth();
        UspsDirectConnector connector = newConnector();
        CustomsCommodityDTO badCoo = CustomsCommodityDTO.builder()
                .description("Verbose-COO")
                .quantity(1)
                .unitValue(new BigDecimal("20"))
                .hsCode("610910")
                .countryOfOrigin("United States")
                .build();
        ShipmentRequestDTO req = intlRequest("GB", List.of(badCoo));
        assertThrows(IllegalArgumentException.class,
                () -> connector.createShipment(req, "real-token", "SANDBOX"));
    }

    // ================================================================
    // Intl country guard
    // ================================================================

    @Test
    void nonIsoAlpha2RecipientCountryThrows() {
        seedTenantAndPaymentAuth();
        UspsDirectConnector connector = newConnector();
        ShipmentRequestDTO req = intlRequest("United Kingdom", List.of(goodCommodity()));
        assertThrows(IllegalArgumentException.class,
                () -> connector.createShipment(req, "real-token", "SANDBOX"));
    }

    // ================================================================
    // Happy-path payload shape
    // ================================================================

    @Test
    @SuppressWarnings("unchecked")
    void buildIntlLabelRequestBody_pins_all_required_slots() {
        UspsDirectConnector connector = newConnector();
        ShipmentRequestDTO req = intlRequest("GB", List.of(goodCommodity()));
        UspsDirectConnector.TenantIdentifiers tenant = new UspsDirectConnector
                .TenantIdentifiers("EPS-INTL-1", "CRID-INTL-1", "MID-INTL-1");
        Map<String, Object> body = connector.buildIntlLabelRequestBody(req, tenant);

        // top-level destinationCountryCode echo (USPS intl endpoint contract)
        assertEquals("GB", body.get("destinationCountryCode"),
                "destinationCountryCode must be uppercase ISO alpha-2 at top level");

        // senderInfo — CRID + MID from tenant row
        Map<String, Object> senderInfo = (Map<String, Object>) body.get("senderInfo");
        assertEquals("CRID-INTL-1", senderInfo.get("CRID"));
        assertEquals("MID-INTL-1", senderInfo.get("MID"));

        // paymentInfo — EPS account
        Map<String, Object> paymentInfo = (Map<String, Object>) body.get("paymentInfo");
        assertEquals("EPS-INTL-1", paymentInfo.get("accountNumber"));
        assertEquals("EPS", paymentInfo.get("accountType"));
        assertEquals("USPS_ACCOUNT", paymentInfo.get("paymentMethod"));

        // packageDescription — intl default service is PMI, not GA
        Map<String, Object> packageDescription = (Map<String, Object>) body.get("packageDescription");
        assertEquals("PRIORITY_MAIL_INTERNATIONAL", packageDescription.get("mailClass"));

        // customsForm — commodities + contentType from block
        Map<String, Object> customsForm = (Map<String, Object>) body.get("customsForm");
        assertNotNull(customsForm, "customsForm block is required");
        assertEquals("MERCHANDISE", customsForm.get("contentType"),
                "reasonForExport=SALE maps to USPS MERCHANDISE");
        assertEquals("NONE", customsForm.get("restriction"));
        assertEquals("RETURN", customsForm.get("nonDeliveryOption"));

        List<Map<String, Object>> commodities = (List<Map<String, Object>>) customsForm.get("commodities");
        assertEquals(1, commodities.size());
        Map<String, Object> row = commodities.get(0);
        assertEquals("Cotton t-shirt", row.get("description"));
        assertEquals(2, row.get("quantity"));
        assertEquals("610910", row.get("hsTariffNumber"),
                "HS code must be dash-stripped");
        assertEquals("US", row.get("countryOfOrigin"),
                "COO must be uppercase");
    }

    @Test
    @SuppressWarnings("unchecked")
    void customsFormToMap_omits_optional_fields_when_blank() {
        UspsDirectConnector connector = newConnector();
        // certificate / license / invoice numbers are null on our block
        // by default — customsFormToMap must not emit empty strings.
        ShipmentRequestDTO req = intlRequest("GB", List.of(goodCommodity()));
        UspsDirectConnector.TenantIdentifiers tenant = new UspsDirectConnector
                .TenantIdentifiers("EPS-INTL-1", "CRID-INTL-1", "MID-INTL-1");
        Map<String, Object> body = connector.buildIntlLabelRequestBody(req, tenant);
        Map<String, Object> customsForm = (Map<String, Object>) body.get("customsForm");

        assertFalse(customsForm.containsKey("certificateNumber"),
                "blank certificateNumber must be omitted");
        assertFalse(customsForm.containsKey("licenseNumber"),
                "blank licenseNumber must be omitted");
        assertFalse(customsForm.containsKey("invoiceNumber"),
                "blank invoiceNumber must be omitted");
    }

    // ================================================================
    // Response parser — customsFormImage
    // ================================================================

    @Test
    void parseIntlLabelResponse_merchandise_fixture_extracts_tracking_and_postage() throws Exception {
        String fixture = loadFixture("usps/v3/intl-labels/intl_label_response_merchandise.json");
        UspsDirectConnector connector = newConnector();
        ShipmentResult result = connector.parseIntlLabelResponse(fixture, intlRequest("GB", List.of(goodCommodity())));

        assertNotNull(result);
        assertEquals("CP123456789US", result.trackingNumber());
        assertEquals(0, result.shippingCost().compareTo(new BigDecimal("42.15")));
        assertNotNull(result.labelPdf(), "labelImage must land in labelPdf");
        assertTrue(result.labelPdf().startsWith("JVBERi0"),
                "labelPdf must be PDF base64 header");
        assertNotNull(result.rawResponse(), "rawResponse must carry the full JSON for downstream extract");
    }

    @Test
    void extractCustomsFormImage_returns_base64_from_fixture() throws Exception {
        String fixture = loadFixture("usps/v3/intl-labels/intl_label_response_merchandise.json");
        UspsDirectConnector connector = newConnector();
        String image = connector.extractCustomsFormImage(fixture);
        assertNotNull(image, "customsFormImage must be extractable from the raw response");
        assertTrue(image.startsWith("JVBERi0"),
                "customsFormImage must be PDF base64; got prefix: "
                        + image.substring(0, Math.min(8, image.length())));
    }

    @Test
    void extractCustomsFormImage_gift_fixture_yields_image() throws Exception {
        String fixture = loadFixture("usps/v3/intl-labels/intl_label_response_gift.json");
        UspsDirectConnector connector = newConnector();
        String image = connector.extractCustomsFormImage(fixture);
        assertNotNull(image);
    }

    @Test
    void extractCustomsFormImage_null_body_yields_null() {
        UspsDirectConnector connector = newConnector();
        assertTrue(connector.extractCustomsFormImage(null) == null);
        assertTrue(connector.extractCustomsFormImage("") == null);
        assertTrue(connector.extractCustomsFormImage("{}") == null);
    }

    // ================================================================
    // Retry behaviour
    // ================================================================

    @Test
    void executeIntlLabelPost_gives_up_when_seam_returns_null_and_throws_ISE() {
        seedTenantAndPaymentAuth();
        AtomicInteger calls = new AtomicInteger();
        UspsDirectConnector connector = new UspsDirectConnector(
                props, objectMapper, tokenCache, paymentAuthCache, jdbc) {
            @Override
            String executeIntlLabelPost(String url, Map<String, Object> body,
                                         String accessToken, Map<String, String> extraHeaders) {
                calls.incrementAndGet();
                return null;  // simulate 429-all-retries-exhausted
            }
        };
        connector.setCustomsFormBuilder(new UspsCustomsFormBuilder());
        ShipmentRequestDTO req = intlRequest("GB", List.of(goodCommodity()));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> connector.createShipment(req, "real-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("returned no response"),
                "message must explain the exhausted-retries state; got: " + ex.getMessage());
        assertEquals(1, calls.get(),
                "seam gets called once per label request (single-piece is our current shape)");
    }

    @Test
    void paymentAuthMintFailure_shortCircuits_before_wire_call() {
        // Payment-auth mint returns Optional.empty() → connector throws
        // IllegalStateException BEFORE hitting the intl branch. Verifies
        // both the domestic + intl paths share the same guard.
        doAnswer(invocation -> {
            org.springframework.jdbc.core.ResultSetExtractor<?> extractor =
                    invocation.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getString("acct")).thenReturn("EPS-1");
            when(rs.getString("crid")).thenReturn("CRID-1");
            when(rs.getString("mid")).thenReturn("MID-1");
            return extractor.extractData(rs);
        }).when(jdbc).query(anyString(),
                any(org.springframework.jdbc.core.ResultSetExtractor.class),
                any(Object[].class));
        when(paymentAuthCache.getToken(anyString(), anyString(), anyString(),
                        anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());
        UspsDirectConnector connector = newConnector();
        ShipmentRequestDTO req = intlRequest("GB", List.of(goodCommodity()));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> connector.createShipment(req, "real-token", "SANDBOX"));
        assertTrue(ex.getMessage().contains("payment-authorization"),
                "message must explain the payment-auth failure; got: " + ex.getMessage());
    }

    // ================================================================
    // Fixture helper
    // ================================================================

    private static String loadFixture(String resourcePath) throws IOException {
        try (java.io.InputStream in = Objects.requireNonNull(
                UspsDirectIntlLabelTest.class.getClassLoader().getResourceAsStream(resourcePath),
                "fixture not on classpath: " + resourcePath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
