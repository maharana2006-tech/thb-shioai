package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.service.carriers.CarrierConnector.ValidateShipmentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR δ.1 — parser tests for FedEx
 * {@code /ship/v1/shipments/packages/validate} responses. Covers the
 * three verdict branches (EXACT / CORRECTED / NOT_FOUND) plus the
 * top-level {@code errors[]} shape returned on 4xx rejections.
 */
class FedExConnectorValidateShipmentTest {

    private FedExConnector connector;

    @BeforeEach
    void setUp() {
        CarrierProperties props = new CarrierProperties();
        connector = new FedExConnector(props, new ObjectMapper(),
                new com.multiship.backend.service.fx.FxRateService() {
                    @Override public java.util.Optional<java.math.BigDecimal> rate(String from, String to) { return java.util.Optional.empty(); }
                    @Override public java.util.Optional<java.math.BigDecimal> convert(java.math.BigDecimal amount, String from, String to) { return java.util.Optional.empty(); }
                    @Override public boolean supports(String currency) { return false; }
                });
    }

    @Test
    void emptyAlertsIsExactAndValid() {
        String response = "{\"output\":{\"alerts\":[]}}";
        ValidateShipmentResult r = connector.parseFedExValidateShipmentResponse(response);
        assertTrue(r.valid());
        assertEquals("EXACT", r.matchLevel());
        assertEquals("SHIPMENT", r.kind());
        assertTrue(r.warnings().isEmpty());
        assertTrue(r.errors().isEmpty());
    }

    @Test
    void missingOutputBlockIsExactAndValid() {
        ValidateShipmentResult r = connector.parseFedExValidateShipmentResponse("{}");
        assertTrue(r.valid());
        assertEquals("EXACT", r.matchLevel());
    }

    @Test
    void warningAlertMapsToCorrectedAndValid() {
        String response = "{\"output\":{\"alerts\":["
                + "{\"code\":\"SHIPMENT.PACKAGES.DIMENSIONS.RECOMMENDED\","
                + "\"alertType\":\"WARNING\","
                + "\"message\":\"Recommended package dimensions were not provided.\"}"
                + "]}}";
        ValidateShipmentResult r = connector.parseFedExValidateShipmentResponse(response);
        assertTrue(r.valid());
        assertEquals("CORRECTED", r.matchLevel());
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("SHIPMENT.PACKAGES.DIMENSIONS.RECOMMENDED"));
        assertTrue(r.errors().isEmpty());
    }

    @Test
    void errorAlertMapsToNotFoundAndInvalid() {
        String response = "{\"output\":{\"alerts\":["
                + "{\"code\":\"PACKAGINGTYPE.VALIDATION.ERROR\","
                + "\"alertType\":\"ERROR\","
                + "\"message\":\"Packaging type invalid for service.\"}"
                + "]}}";
        ValidateShipmentResult r = connector.parseFedExValidateShipmentResponse(response);
        assertFalse(r.valid());
        assertEquals("NOT_FOUND", r.matchLevel());
        assertEquals(1, r.errors().size());
        assertTrue(r.errors().get(0).contains("PACKAGINGTYPE.VALIDATION.ERROR"));
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void mixedAlertsSplitByType() {
        String response = "{\"output\":{\"alerts\":["
                + "{\"code\":\"W1\",\"alertType\":\"WARNING\",\"message\":\"warn\"},"
                + "{\"code\":\"N1\",\"alertType\":\"NOTE\",\"message\":\"note skipped\"},"
                + "{\"code\":\"E1\",\"alertType\":\"ERROR\",\"message\":\"err\"}"
                + "]}}";
        ValidateShipmentResult r = connector.parseFedExValidateShipmentResponse(response);
        // Error dominates.
        assertFalse(r.valid());
        assertEquals("NOT_FOUND", r.matchLevel());
        assertEquals(1, r.warnings().size(), "NOTE should be dropped, WARNING kept");
        assertEquals(1, r.errors().size());
    }

    @Test
    void malformedResponseIsErrorNotThrow() {
        ValidateShipmentResult r = connector.parseFedExValidateShipmentResponse("not-json");
        assertFalse(r.valid());
        assertEquals("ERROR", r.matchLevel());
    }

    // PR #533 — humanizeFedExFieldPath golden values covering the main
    // envelope prefixes + per-package indexing.

    @Test
    void humanize_recipientPostalCode() {
        assertEquals("Recipient postal code",
                FedExConnector.humanizeFedExFieldPath(
                        "requestedShipment.recipients[0].address.postalCode"));
    }

    @Test
    void humanize_shipperState() {
        assertEquals("Shipper state",
                FedExConnector.humanizeFedExFieldPath(
                        "requestedShipment.shipper.address.stateOrProvinceCode"));
    }

    @Test
    void humanize_serviceType() {
        assertEquals("Service type",
                FedExConnector.humanizeFedExFieldPath(
                        "requestedShipment.serviceType"));
    }

    @Test
    void humanize_packageWeightWithIndex() {
        assertEquals("Package 2 weight",
                FedExConnector.humanizeFedExFieldPath(
                        "requestedShipment.requestedPackageLineItems[1].weight.value"));
    }

    @Test
    void humanize_unknownPathFallsThrough() {
        assertEquals("someObscureField",
                FedExConnector.humanizeFedExFieldPath(
                        "requestedShipment.someObscureField"));
    }

    @Test
    void humanize_blankIsBlank() {
        assertEquals("", FedExConnector.humanizeFedExFieldPath(""));
        assertEquals("", FedExConnector.humanizeFedExFieldPath(null));
    }

    // ─── PR #557 audit — parameterList humanization + dedup ─────────

    @org.junit.jupiter.api.Test
    void buildFedExErrorPrefix_knownKey_returnsHumanContext() throws Exception {
        // Matches the exact shape FedEx returns for commodity-value errors
        // — {key: COMMODITY_INDEX, value: 1}. Pre-fix the operator saw
        // "COMMODITY_INDEX — 1"; now context becomes "Commodity line 1".
        ObjectMapper om = new ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode params = om.readTree(
                "[{\"key\":\"COMMODITY_INDEX\",\"value\":\"1\"}]");
        assertEquals("Commodity line 1", FedExConnector.buildFedExErrorPrefix(params));
    }

    @org.junit.jupiter.api.Test
    void buildFedExErrorPrefix_unknownKey_isDropped() throws Exception {
        // Unknown keys were previously surfaced verbatim ("MYSTERY — 7").
        // Now silently dropped so we don't leak wire-y strings.
        ObjectMapper om = new ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode params = om.readTree(
                "[{\"key\":\"MYSTERY_KEY\",\"value\":\"7\"}]");
        assertEquals("", FedExConnector.buildFedExErrorPrefix(params));
    }

    @org.junit.jupiter.api.Test
    void buildFedExErrorPrefix_multipleKnownKeys_joinedComma() throws Exception {
        ObjectMapper om = new ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode params = om.readTree(
                "[{\"key\":\"PACKAGE_INDEX\",\"value\":\"2\"},"
                + "{\"key\":\"COMMODITY_INDEX\",\"value\":\"3\"}]");
        assertEquals("Package 2, Commodity line 3",
                FedExConnector.buildFedExErrorPrefix(params));
    }

    @org.junit.jupiter.api.Test
    void buildFedExErrorPrefix_nullOrEmpty_returnsEmpty() throws Exception {
        assertEquals("", FedExConnector.buildFedExErrorPrefix(null));
        ObjectMapper om = new ObjectMapper();
        assertEquals("", FedExConnector.buildFedExErrorPrefix(om.readTree("[]")));
    }
}
