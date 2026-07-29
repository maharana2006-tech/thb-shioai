package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 25 — Print Return Label wire emission across all four carriers.
 * One test file, one round-trip per carrier, so a future reviewer
 * looking at "how is a return label emitted" gets the whole matrix
 * in one place.
 *
 * <p>Uses reflection into each connector's private payload builder so
 * we don't need a live sandbox — we're asserting on the request shape,
 * not the round-tripped response.
 */
class ReturnLabelTest {

    private static FxRateService noFx() {
        return new FxRateService() {
            @Override public Optional<BigDecimal> rate(String f, String t) { return Optional.empty(); }
            @Override public Optional<BigDecimal> convert(BigDecimal a, String f, String t) { return Optional.empty(); }
            @Override public boolean supports(String c) { return false; }
        };
    }

    private static ShipmentRequestDTO baseRequest() {
        return ShipmentRequestDTO.builder()
                .carrierCode("UPS")
                .accountNumber("A12345")
                .serviceType("03")
                .packageType("02")
                .weight(new BigDecimal("2.5"))
                .weightUnit("LB")
                .shipperName("Acme Returns").shipperPhone("5551234567")
                .shipperAddressLine1("1 Return Depot").shipperCity("Louisville")
                .shipperState("KY").shipperPostalCode("40209").shipperCountryCode("US")
                .recipientName("Jane Doe").recipientPhone("5559876543")
                .recipientAddressLine1("42 Broadway").recipientCity("New York")
                .recipientState("NY").recipientPostalCode("10001").recipientCountryCode("US")
                .referenceNumber("RMA-1001")
                .build();
    }

    /* -------------------------- UPS -------------------------- */

    @SuppressWarnings("unchecked")
    private Map<String, Object> upsShipmentBlock(ShipmentRequestDTO r) throws Exception {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        Method m = UpsConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(c, r);
        Map<String, Object> shipmentRequest = (Map<String, Object>) payload.get("ShipmentRequest");
        return (Map<String, Object>) shipmentRequest.get("Shipment");
    }

    @Test
    void upsEmitsReturnServiceCode8WhenIsReturnTrue() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIsReturn(true);
        Map<String, Object> shipment = upsShipmentBlock(r);
        Object returnService = shipment.get("ReturnService");
        assertNotNull(returnService, "UPS Shipment.ReturnService must be present on return labels");
        assertTrue(returnService instanceof Map, "ReturnService should be a Map");
        @SuppressWarnings("unchecked")
        Map<String, Object> rs = (Map<String, Object>) returnService;
        // Code 8 = "Print Return Label" (paper-based). See UPS Ship API
        // "ReturnServiceCode" enum.
        assertEquals("8", rs.get("Code"));
    }

    @Test
    void upsOmitsReturnServiceWhenIsReturnFalse() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIsReturn(false);
        assertNull(upsShipmentBlock(r).get("ReturnService"));
    }

    @Test
    void upsOmitsReturnServiceWhenIsReturnNull() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        assertNull(r.getIsReturn(), "sanity: default should be null");
        assertNull(upsShipmentBlock(r).get("ReturnService"));
    }

    /* -------------------------- FedEx -------------------------- */

    @SuppressWarnings("unchecked")
    private Map<String, Object> fedexRequestedShipment(ShipmentRequestDTO r) throws Exception {
        FedExConnector c = new FedExConnector(new CarrierProperties(), new ObjectMapper(), noFx());
        Method m = FedExConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(c, r);
        return (Map<String, Object>) payload.get("requestedShipment");
    }

    @Test
    void fedexEmitsReturnedShipmentDetailAndFlipsPickupTypeWhenIsReturnTrue() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIsReturn(true);
        Map<String, Object> requested = fedexRequestedShipment(r);

        // pickupType flips from USE_SCHEDULED_PICKUP → CONTACT_FEDEX_TO_SCHEDULE
        // so the customer doesn't need a standing pickup.
        assertEquals("CONTACT_FEDEX_TO_SCHEDULE", requested.get("pickupType"));

        Object returned = requested.get("returnedShipmentDetail");
        assertNotNull(returned, "FedEx returnedShipmentDetail must be present on return labels");
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) returned;
        assertEquals("PRINT_RETURN_LABEL", detail.get("returnType"));
    }

    @Test
    void fedexKeepsScheduledPickupAndOmitsReturnedShipmentDetailWhenIsReturnFalse() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setIsReturn(false);
        Map<String, Object> requested = fedexRequestedShipment(r);
        assertEquals("USE_SCHEDULED_PICKUP", requested.get("pickupType"));
        assertNull(requested.get("returnedShipmentDetail"));
    }

    @Test
    void fedexOmitsReturnedShipmentDetailWhenIsReturnNull() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        assertNull(r.getIsReturn());
        assertNull(fedexRequestedShipment(r).get("returnedShipmentDetail"));
    }

    /* -------------------------- USPS / Stamps SWSIM -------------------------- */

    private String stampsCreateIndiciumEnvelope(ShipmentRequestDTO r) throws Exception {
        StampsConnector c = new StampsConnector(new CarrierProperties(), new ObjectMapper());
        Method m = StampsConnector.class.getDeclaredMethod("buildCreateIndiciumEnvelope",
                ShipmentRequestDTO.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(c, r, "AUTH-XYZ");
    }

    @Test
    void stampsEmitsIsReturnLabelWhenIsReturnTrue() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("USPS GA");
        r.setPackageType("Package");
        r.setIsReturn(true);
        String xml = stampsCreateIndiciumEnvelope(r);
        assertTrue(xml.contains("<IsReturnLabel>true</IsReturnLabel>"),
                "SWSIM CreateIndicium must include <IsReturnLabel>true</IsReturnLabel> for return labels; got: " + xml);
    }

    @Test
    void stampsOmitsIsReturnLabelWhenIsReturnFalse() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("USPS GA");
        r.setPackageType("Package");
        r.setIsReturn(false);
        assertFalse(stampsCreateIndiciumEnvelope(r).contains("<IsReturnLabel"));
    }

    @Test
    void stampsOmitsIsReturnLabelWhenIsReturnNull() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("USPS GA");
        r.setPackageType("Package");
        assertNull(r.getIsReturn());
        assertFalse(stampsCreateIndiciumEnvelope(r).contains("<IsReturnLabel"));
    }

    /* -------------------------- DHL Express -------------------------- */

    @SuppressWarnings("unchecked")
    private Map<String, Object> dhlPayload(ShipmentRequestDTO r) throws Exception {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        Method m = DhlConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(c, r);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dhlFlipsPickupIsRequestedWhenIsReturnTrue() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("P");
        r.setPackageType("3BX");
        r.setIsReturn(true);
        Map<String, Object> payload = dhlPayload(r);
        Map<String, Object> pickup = (Map<String, Object>) payload.get("pickup");
        assertNotNull(pickup);
        // DHL Express Global Return: pickup.isRequested=true schedules a
        // courier collection from the customer's address.
        assertEquals(Boolean.TRUE, pickup.get("isRequested"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dhlKeepsPickupFalseWhenIsReturnFalse() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("P");
        r.setPackageType("3BX");
        r.setIsReturn(false);
        Map<String, Object> pickup = (Map<String, Object>) dhlPayload(r).get("pickup");
        assertEquals(Boolean.FALSE, pickup.get("isRequested"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dhlKeepsPickupFalseWhenIsReturnNull() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setServiceType("P");
        r.setPackageType("3BX");
        assertNull(r.getIsReturn());
        Map<String, Object> pickup = (Map<String, Object>) dhlPayload(r).get("pickup");
        assertEquals(Boolean.FALSE, pickup.get("isRequested"));
    }
}
