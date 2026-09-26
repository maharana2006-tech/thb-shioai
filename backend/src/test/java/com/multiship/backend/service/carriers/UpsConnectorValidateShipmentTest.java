package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.CarrierConnector.ValidateShipmentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR δ.1 — parser tests for UPS ShipConfirm-with-validate responses +
 * a golden-value check that the {@code RequestOption} switch actually
 * flips on the wire (payload builder overload).
 */
class UpsConnectorValidateShipmentTest {

    private UpsConnector connector;

    @BeforeEach
    void setUp() {
        CarrierProperties props = new CarrierProperties();
        connector = new UpsConnector(props, new ObjectMapper());
    }

    @Test
    void code1NoAlertsIsExact() {
        String response = "{\"ShipmentResponse\":{\"Response\":{"
                + "\"ResponseStatus\":{\"Code\":\"1\",\"Description\":\"Success\"}}}}";
        ValidateShipmentResult r = connector.parseUpsValidateShipmentResponse(response);
        assertTrue(r.valid());
        assertEquals("EXACT", r.matchLevel());
        assertEquals("SHIPMENT", r.kind());
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void code1WithAlertArrayMapsToCorrected() {
        String response = "{\"ShipmentResponse\":{\"Response\":{"
                + "\"ResponseStatus\":{\"Code\":\"1\",\"Description\":\"Success\"},"
                + "\"Alert\":[{\"Code\":\"120900\",\"Description\":\"User Id and Shipper Number combination is not valid.\"}]"
                + "}}}";
        ValidateShipmentResult r = connector.parseUpsValidateShipmentResponse(response);
        assertTrue(r.valid());
        assertEquals("CORRECTED", r.matchLevel());
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("120900"));
    }

    @Test
    void code1WithAlertObjectMapsToCorrected() {
        // UPS sometimes returns a bare object instead of an array when
        // exactly one alert is present.
        String response = "{\"ShipmentResponse\":{\"Response\":{"
                + "\"ResponseStatus\":{\"Code\":\"1\",\"Description\":\"Success\"},"
                + "\"Alert\":{\"Code\":\"111057\",\"Description\":\"Your invoice may vary from the displayed rate.\"}"
                + "}}}";
        ValidateShipmentResult r = connector.parseUpsValidateShipmentResponse(response);
        assertTrue(r.valid());
        assertEquals("CORRECTED", r.matchLevel());
        assertEquals(1, r.warnings().size());
    }

    @Test
    void nonSuccessCodeMapsToNotFound() {
        String response = "{\"ShipmentResponse\":{\"Response\":{"
                + "\"ResponseStatus\":{\"Code\":\"0\",\"Description\":\"Failure\"}}}}";
        ValidateShipmentResult r = connector.parseUpsValidateShipmentResponse(response);
        assertFalse(r.valid());
        assertEquals("NOT_FOUND", r.matchLevel());
    }

    @Test
    void malformedResponseIsError() {
        ValidateShipmentResult r = connector.parseUpsValidateShipmentResponse("not-json");
        assertFalse(r.valid());
        assertEquals("ERROR", r.matchLevel());
    }

    /** Validate now asks the Rating API — nothing is created at UPS. */
    @Test
    void aRateReplyIsReadAndTheRateDisclaimerIsNotAWarning() {
        String ok = "{\"RateResponse\":{\"Response\":{\"ResponseStatus\":{\"Code\":\"1\",\"Description\":\"Success\"},"
                + "\"Alert\":[{\"Code\":\"110971\",\"Description\":\"Your invoice may vary from the displayed reference rates\"}]}}}";
        ValidateShipmentResult r = connector.parseUpsValidateShipmentResponse(ok);
        assertTrue(r.valid());
        assertEquals("EXACT", r.matchLevel());
        assertTrue(r.warnings().isEmpty());

        String classified = "{\"RateResponse\":{\"Response\":{\"ResponseStatus\":{\"Code\":\"1\"},"
                + "\"Alert\":[{\"Code\":\"110971\",\"Description\":\"x\"},"
                + "{\"Code\":\"110920\",\"Description\":\"Ship To Address Classification is changed from Commercial to Residential\"}]}}}";
        ValidateShipmentResult c = connector.parseUpsValidateShipmentResponse(classified);
        assertEquals("CORRECTED", c.matchLevel());
        assertEquals(1, c.warnings().size());
        assertTrue(c.warnings().get(0).startsWith("110920"));
    }

    @Test
    void payloadBuilderFlipsRequestOptionForValidate() throws Exception {
        Method m = UpsConnector.class.getDeclaredMethod(
                "buildShipmentPayload", ShipmentRequestDTO.class, String.class);
        m.setAccessible(true);
        ShipmentRequestDTO request = baseRequest();
        @SuppressWarnings("unchecked")
        Map<String, Object> validatePayload = (Map<String, Object>) m.invoke(connector, request, "validate");
        @SuppressWarnings("unchecked")
        Map<String, Object> shipPayload = (Map<String, Object>) m.invoke(connector, request, "nonvalidate");
        assertEquals("validate", requestOption(validatePayload));
        assertEquals("nonvalidate", requestOption(shipPayload));
    }

    @SuppressWarnings("unchecked")
    private String requestOption(Map<String, Object> payload) {
        Map<String, Object> req = (Map<String, Object>) ((Map<String, Object>) payload.get("ShipmentRequest")).get("Request");
        return (String) req.get("RequestOption");
    }

    private ShipmentRequestDTO baseRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("UPS")
                .accountNumber("A99999")
                .serviceType("03")
                .packageType("02")
                .weight(new BigDecimal("2.5"))
                .weightUnit("LB")
                .shipperName("Acme")
                .shipperPhone("5551234567")
                .shipperAddressLine1("1 Warehouse Way")
                .shipperCity("Louisville").shipperState("KY").shipperPostalCode("40209")
                .shipperCountryCode("US")
                .recipientName("Jane Doe")
                .recipientPhone("5559876543")
                .recipientAddressLine1("42 High Street")
                .recipientCity("Portland").recipientState("OR").recipientPostalCode("97229")
                .recipientCountryCode("US")
                .referenceNumber("PO-1001")
                .build();
    }

    // ─── UPS Time-in-Transit response parser (Sprint / TiT swap) ────────

    @Test
    void tit_requestedServiceInResponse_isExactValid() {
        String response = "{\"emsResponse\":{\"services\":["
                + "{\"serviceLevel\":\"03\",\"serviceLevelDescription\":\"Ground\",\"businessTransitDays\":\"5\"},"
                + "{\"serviceLevel\":\"02\",\"serviceLevelDescription\":\"2nd Day Air\",\"businessTransitDays\":\"2\"}]}}";
        ValidateShipmentResult r = connector.parseUpsTitResponse("03", response);
        assertTrue(r.valid());
        assertEquals("EXACT", r.matchLevel());
        assertTrue(r.message().contains("Ground"));
        assertTrue(r.message().contains("5 business day"));
    }

    @Test
    void tit_requestedServiceMissing_lists_available_and_fails() {
        String response = "{\"emsResponse\":{\"services\":["
                + "{\"serviceLevel\":\"02\",\"serviceLevelDescription\":\"2nd Day Air\"},"
                + "{\"serviceLevel\":\"01\",\"serviceLevelDescription\":\"Next Day Air\"}]}}";
        ValidateShipmentResult r = connector.parseUpsTitResponse("03", response);
        assertFalse(r.valid());
        assertEquals("NOT_FOUND", r.matchLevel());
        assertTrue(r.message().contains("2nd Day Air"));
        assertTrue(r.message().contains("Next Day Air"));
    }

    @Test
    void tit_noServices_isFail() {
        ValidateShipmentResult r = connector.parseUpsTitResponse("03", "{\"emsResponse\":{\"services\":[]}}");
        assertFalse(r.valid());
        assertEquals("NOT_FOUND", r.matchLevel());
    }
}
