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
}
