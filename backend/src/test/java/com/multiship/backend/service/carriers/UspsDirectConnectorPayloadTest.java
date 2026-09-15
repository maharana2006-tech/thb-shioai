package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.CarrierConnector.ShipmentResult;
import com.multiship.backend.service.carriers.usps.UspsOAuthTokenCache;
import com.multiship.backend.service.carriers.usps.UspsPaymentAuthCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Payload-shape tests for {@link UspsDirectConnector}'s label + rate
 * request builders and its label-response parser.
 *
 * <p>USPS v3 is strict about the envelope shape (missing / mis-named
 * fields → cryptic 400 from apis.usps.com). This test file pins the
 * exact JSON we intend to POST — a future edit that renames
 * {@code senderInfo.CRID} or drops {@code paymentInfo.accountType} fails
 * here before it silently ships wrong labels.
 */
class UspsDirectConnectorPayloadTest {

    private UspsDirectConnector connector;

    @BeforeEach
    void setUp() {
        CarrierProperties props = new CarrierProperties();
        props.setDefaultEnvironment("SANDBOX");
        ObjectMapper om = new ObjectMapper();
        connector = new UspsDirectConnector(
                props, om,
                new UspsOAuthTokenCache(om),
                new UspsPaymentAuthCache(om),
                mock(JdbcTemplate.class));
    }

    private static ShipmentRequestDTO minimalRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("USPS")
                .accountNumber("ACCT-7788")
                .serviceType("USPS_GROUND_ADVANTAGE")
                .packageType("USPS_PACKAGE")
                .weight(new BigDecimal("1"))
                .weightUnit("LB")
                .length(new BigDecimal("6"))
                .width(new BigDecimal("4"))
                .height(new BigDecimal("2"))
                .dimUnit("IN")
                .shipperName("ACME Warehouse")
                .shipperCompany("ACME Inc")
                .shipperPhone("3035551212")
                .shipperAddressLine1("1 Warehouse Rd")
                .shipperCity("Denver")
                .shipperState("CO")
                .shipperPostalCode("80202")
                .shipperCountryCode("US")
                .recipientName("Alice Recipient")
                .recipientPhone("2125551212")
                .recipientAddressLine1("500 Fifth Ave")
                .recipientAddressLine2("Apt 12")
                .recipientCity("New York")
                .recipientState("NY")
                .recipientPostalCode("10018")
                .recipientCountryCode("US")
                .referenceNumber("PO-42")
                .build();
    }

    // ================================================================
    // Label envelope
    // ================================================================

    @Test
    @SuppressWarnings("unchecked")
    void buildLabelRequestBody_carries_all_required_fields() {
        UspsDirectConnector.TenantIdentifiers tenant = new UspsDirectConnector
                .TenantIdentifiers("EPS-ACCT-7788", "CRID-42", "MID-99");
        Map<String, Object> body = connector.buildLabelRequestBody(minimalRequest(), tenant);

        // imageInfo
        Map<String, Object> imageInfo = (Map<String, Object>) body.get("imageInfo");
        assertNotNull(imageInfo, "imageInfo block is required");
        assertEquals("PDF", imageInfo.get("imageType"),
                "default imageType must be PDF");
        assertEquals("4X6", imageInfo.get("labelType"));
        assertEquals("NONE", imageInfo.get("receiptOption"));

        // senderInfo — the CRID + MID identify the mailer at USPS
        Map<String, Object> senderInfo = (Map<String, Object>) body.get("senderInfo");
        assertNotNull(senderInfo, "senderInfo block is required");
        assertEquals("CRID-42", senderInfo.get("CRID"),
                "senderInfo.CRID must be the tenant's CRID");
        assertEquals("MID-99", senderInfo.get("MID"),
                "senderInfo.MID must be the tenant's MID");

        // paymentInfo — EPS debit account
        Map<String, Object> paymentInfo = (Map<String, Object>) body.get("paymentInfo");
        assertNotNull(paymentInfo, "paymentInfo block is required");
        assertEquals("USPS_ACCOUNT", paymentInfo.get("paymentMethod"));
        assertEquals("EPS", paymentInfo.get("accountType"),
                "USPS Direct always debits EPS accounts");
        assertEquals("EPS-ACCT-7788", paymentInfo.get("accountNumber"),
                "paymentInfo.accountNumber must be the tenant EPS account");

        // packageDescription — mail class + physical dimensions
        Map<String, Object> pkg = (Map<String, Object>) body.get("packageDescription");
        assertNotNull(pkg, "packageDescription block is required");
        assertEquals("USPS_GROUND_ADVANTAGE", pkg.get("mailClass"),
                "mailClass must come from request.serviceType");
        assertEquals("MACHINABLE", pkg.get("processingCategory"));
        assertEquals("SP", pkg.get("rateIndicator"));
        assertEquals(1.0, ((Number) pkg.get("weight")).doubleValue(), 0.001,
                "weight must be in pounds (LB → LB no-op)");
        assertEquals(0, ((BigDecimal) pkg.get("length")).compareTo(new BigDecimal("6.000")));
        assertEquals(0, ((BigDecimal) pkg.get("width")).compareTo(new BigDecimal("4.000")));
        assertEquals(0, ((BigDecimal) pkg.get("height")).compareTo(new BigDecimal("2.000")));
        assertTrue(pkg.get("extraServices") instanceof List);

        // toAddress / fromAddress
        Map<String, Object> toAddr = (Map<String, Object>) body.get("toAddress");
        assertNotNull(toAddr, "toAddress required");
        assertEquals("500 Fifth Ave", toAddr.get("streetAddress"));
        assertEquals("Apt 12", toAddr.get("secondaryAddress"));
        assertEquals("New York", toAddr.get("city"));
        assertEquals("NY", toAddr.get("state"));
        assertEquals("10018", toAddr.get("ZIPCode"));
        assertEquals("US", toAddr.get("countryCode"));
        assertEquals("Alice Recipient", toAddr.get("firstName"));
        assertEquals("2125551212", toAddr.get("phone"));

        Map<String, Object> fromAddr = (Map<String, Object>) body.get("fromAddress");
        assertNotNull(fromAddr);
        assertEquals("1 Warehouse Rd", fromAddr.get("streetAddress"));
        assertEquals("Denver", fromAddr.get("city"));
        assertEquals("CO", fromAddr.get("state"));
        assertEquals("80202", fromAddr.get("ZIPCode"));
        assertEquals("US", fromAddr.get("countryCode"));
        assertEquals("ACME Warehouse", fromAddr.get("firstName"));
        assertEquals("ACME Inc", fromAddr.get("firm"),
                "shipper company must land in the firm field");

        // customerReference — the operator's PO / order number
        assertEquals("PO-42", body.get("customerReference"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildLabelRequestBody_kg_weight_converts_to_pounds() {
        // A 1 KG parcel is 2.20462262 LB — the wire body must carry the
        // converted value, not the raw KG number. Skipping this
        // conversion is a real class of bug (F-audit precedent).
        ShipmentRequestDTO req = minimalRequest();
        req.setWeight(new BigDecimal("1"));
        req.setWeightUnit("KG");
        UspsDirectConnector.TenantIdentifiers tenant = new UspsDirectConnector
                .TenantIdentifiers("A", "C", "M");
        Map<String, Object> body = connector.buildLabelRequestBody(req, tenant);
        Map<String, Object> pkg = (Map<String, Object>) body.get("packageDescription");
        double weightLbs = ((Number) pkg.get("weight")).doubleValue();
        assertEquals(2.20, weightLbs, 0.01,
                "1 KG must convert to ~2.20 LB on the wire; got " + weightLbs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildLabelRequestBody_cm_dimensions_convert_to_inches() {
        ShipmentRequestDTO req = minimalRequest();
        req.setLength(new BigDecimal("10"));
        req.setWidth(new BigDecimal("10"));
        req.setHeight(new BigDecimal("10"));
        req.setDimUnit("CM");
        UspsDirectConnector.TenantIdentifiers tenant = new UspsDirectConnector
                .TenantIdentifiers("A", "C", "M");
        Map<String, Object> body = connector.buildLabelRequestBody(req, tenant);
        Map<String, Object> pkg = (Map<String, Object>) body.get("packageDescription");
        BigDecimal lenIn = (BigDecimal) pkg.get("length");
        // 10 CM = 3.937 IN
        assertEquals(0, lenIn.compareTo(new BigDecimal("3.937")),
                "10 CM should convert to ~3.937 IN; got " + lenIn);
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildLabelRequestBody_omits_optional_fields_when_blank() {
        // Optional fields (company, phone, address line 2, reference) must
        // NOT land as empty strings on the wire — USPS rejects the request
        // otherwise. Test with a minimal request.
        ShipmentRequestDTO r = ShipmentRequestDTO.builder()
                .carrierCode("USPS")
                .accountNumber("ACCT-7788")
                .serviceType("USPS_GROUND_ADVANTAGE")
                .packageType("USPS_PACKAGE")
                .weight(new BigDecimal("1"))
                .weightUnit("LB")
                .shipperName("Sender")
                .shipperAddressLine1("1 A St")
                .shipperCity("Denver").shipperState("CO").shipperPostalCode("80202").shipperCountryCode("US")
                .recipientName("Recipient")
                .recipientAddressLine1("2 B St")
                .recipientCity("NYC").recipientState("NY").recipientPostalCode("10001").recipientCountryCode("US")
                .build();

        UspsDirectConnector.TenantIdentifiers tenant = new UspsDirectConnector
                .TenantIdentifiers("A", "C", "M");
        Map<String, Object> body = connector.buildLabelRequestBody(r, tenant);

        assertFalse(body.containsKey("customerReference"),
                "referenceNumber was null → customerReference key must be omitted, not empty-string");

        Map<String, Object> toAddr = (Map<String, Object>) body.get("toAddress");
        assertFalse(toAddr.containsKey("secondaryAddress"),
                "null address line 2 → secondaryAddress key omitted");
        assertFalse(toAddr.containsKey("phone"),
                "null phone → phone key omitted");
        assertFalse(toAddr.containsKey("firm"),
                "null company → firm key omitted");
    }

    // ================================================================
    // Rate envelope
    // ================================================================

    @Test
    void buildRateRequestBody_emits_zip_codes_and_dimensions() {
        UspsDirectConnector.TenantIdentifiers tenant = new UspsDirectConnector
                .TenantIdentifiers("EPS-ACCT", "CRID-1", "MID-1");
        ShipmentRequestDTO req = minimalRequest();
        Map<String, Object> body = connector.buildRateRequestBody(
                req, req.effectivePackages().get(0), tenant);

        assertEquals("80202", body.get("originZIPCode"));
        assertEquals("10018", body.get("destinationZIPCode"));
        assertEquals(1.0, ((Number) body.get("weight")).doubleValue(), 0.001);
        assertEquals(0, ((BigDecimal) body.get("length")).compareTo(new BigDecimal("6.000")));
        assertEquals("USPS_GROUND_ADVANTAGE", body.get("mailClass"),
                "rate request defaults to Ground Advantage so USPS returns the full ladder");
    }

    // ================================================================
    // Label response parser — fixture → ShipmentResult
    // ================================================================

    @Test
    void parseLabelResponse_extracts_tracking_postage_and_pdf() throws Exception {
        String fixture = loadFixture("usps/v3/label_response.json");
        ShipmentResult result = connector.parseLabelResponse(fixture, minimalRequest());

        assertNotNull(result, "parse must never return null on a valid response");
        assertEquals("9400111899223197428490", result.trackingNumber(),
                "trackingNumber must come from labelMetadata.trackingNumber");
        assertNotNull(result.labelPdf(),
                "labelImage must land in labelPdf as base64");
        assertTrue(result.labelPdf().startsWith("JVBERi0"),
                "labelPdf must be the PDF base64 header (%PDF- → JVBERi0)");
        assertEquals(0, result.shippingCost().compareTo(new BigDecimal("8.85")),
                "postage must map to shippingCost");
        assertNotNull(result.trackingUrl(),
                "trackingUrl must be built from the tracking number");
        assertTrue(result.trackingUrl().contains("9400111899223197428490"));
    }

    @Test
    void parseLabelResponse_missing_tracking_still_returns_result_with_nulls() throws Exception {
        // A carrier failure that returns 200 with empty metadata shouldn't
        // NPE — the caller records it as a FAILED_CARRIER upstream.
        ShipmentResult result = connector.parseLabelResponse("{}", minimalRequest());
        assertNotNull(result);
        assertNull(result.trackingNumber());
        assertNull(result.trackingUrl());
    }

    // ================================================================
    // Fixture helper
    // ================================================================

    private static String loadFixture(String resourcePath) throws IOException {
        try (java.io.InputStream in = Objects.requireNonNull(
                UspsDirectConnectorPayloadTest.class.getClassLoader().getResourceAsStream(resourcePath),
                "fixture not on classpath: " + resourcePath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
