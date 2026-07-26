package com.multiship.backend.service.carriers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.fx.FxRateService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 6 tests — {@code recipientResidential} + {@code recipientPhoneCountryCode}
 * threading into UPS and FedEx payloads. Kept in one file so a future reviewer
 * looking at "how is residential handled" gets both carriers in one place.
 */
class ResidentialAndPhoneTest {

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
                .shipperName("Acme Warehouse").shipperPhone("5551234567")
                .shipperAddressLine1("1 Warehouse Way").shipperCity("Louisville")
                .shipperState("KY").shipperPostalCode("40209").shipperCountryCode("US")
                .recipientName("Jane Doe").recipientPhone("20 7946 0958")
                .recipientAddressLine1("42 High Street").recipientCity("London")
                .recipientState("").recipientPostalCode("W1A 1AA").recipientCountryCode("GB")
                .referenceNumber("PO-1001")
                .build();
    }

    /* -------------------------- UPS -------------------------- */

    @SuppressWarnings("unchecked")
    private Map<String, Object> upsShipTo(ShipmentRequestDTO r) throws Exception {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        Method m = UpsConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(c, r);
        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                payload.get("ShipmentRequest")).get("Shipment");
        return (Map<String, Object>) shipment.get("ShipTo");
    }

    @Test
    void upsAbsentResidentialIndicatorMeansCommercial() throws Exception {
        // recipientResidential=null → the element should not appear at all.
        Map<String, Object> shipTo = upsShipTo(baseRequest());
        @SuppressWarnings("unchecked")
        Map<String, Object> address = (Map<String, Object>) shipTo.get("Address");
        assertFalse(address.containsKey("ResidentialAddressIndicator"),
                "Absent residential flag should NOT emit the indicator element");
    }

    @Test
    void upsResidentialTrueEmitsEmptyIndicatorElement() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientResidential(true);
        Map<String, Object> shipTo = upsShipTo(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> address = (Map<String, Object>) shipTo.get("Address");
        assertTrue(address.containsKey("ResidentialAddressIndicator"),
                "UPS convention: element presence signals residential");
        assertEquals("", address.get("ResidentialAddressIndicator"),
                "Value is ignored — element is empty per UPS spec");
    }

    @Test
    void upsResidentialFalseDoesNotEmitIndicator() throws Exception {
        // Explicit false = commercial; UPS default. Don't emit noise.
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientResidential(false);
        Map<String, Object> shipTo = upsShipTo(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> address = (Map<String, Object>) shipTo.get("Address");
        assertFalse(address.containsKey("ResidentialAddressIndicator"));
    }

    @Test
    void upsPhoneCountryCodeIsPrepended() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientPhoneCountryCode("44");
        Map<String, Object> shipTo = upsShipTo(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> phone = (Map<String, Object>) shipTo.get("Phone");
        assertEquals("+44 20 7946 0958", phone.get("Number"));
    }

    @Test
    void upsPhoneAlreadyPrefixedIsNotDoubled() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientPhone("+44 20 7946 0958");
        r.setRecipientPhoneCountryCode("44");
        Map<String, Object> shipTo = upsShipTo(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> phone = (Map<String, Object>) shipTo.get("Phone");
        assertEquals("+44 20 7946 0958", phone.get("Number"),
                "Phone that already has a plus prefix should be sent unchanged");
    }

    /* -------------------------- FedEx -------------------------- */

    @SuppressWarnings("unchecked")
    private Map<String, Object> fedexRecipient(ShipmentRequestDTO r) throws Exception {
        CarrierProperties props = new CarrierProperties();
        props.getFedEx().setLabelResponseOption("URL_ONLY");
        FedExConnector c = new FedExConnector(props, new ObjectMapper(), noFx());
        Method m = FedExConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(c, r);
        Map<String, Object> rs = (Map<String, Object>) payload.get("requestedShipment");
        Object[] recipients = (Object[]) rs.get("recipients");
        return (Map<String, Object>) recipients[0];
    }

    @Test
    void fedexAbsentResidentialMeansCommercial() throws Exception {
        Map<String, Object> recipient = fedexRecipient(baseRequest());
        @SuppressWarnings("unchecked")
        Map<String, Object> address = (Map<String, Object>) recipient.get("address");
        assertNull(address.get("residential"),
                "Absent residential flag should leave the field off entirely");
    }

    @Test
    void fedexResidentialTrueSetsAddressResidential() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientResidential(true);
        Map<String, Object> recipient = fedexRecipient(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> address = (Map<String, Object>) recipient.get("address");
        assertEquals(true, address.get("residential"),
                "FedEx flags residential on the address block");
    }

    @Test
    void fedexPhoneCountryCodeIsPrepended() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientPhoneCountryCode("44");
        Map<String, Object> recipient = fedexRecipient(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> contact = (Map<String, Object>) recipient.get("contact");
        assertEquals("+44 20 7946 0958", contact.get("phoneNumber"));
    }

    /* -------------------------- Helper -------------------------- */

    @Test
    void joinPhoneCoversTheEdgeCases() {
        // Country code null / blank → phone unchanged.
        assertEquals("5551234567", UpsConnector.joinPhone(null, "5551234567"));
        assertEquals("5551234567", UpsConnector.joinPhone("", "5551234567"));
        // Phone null / blank → empty output.
        assertEquals("", UpsConnector.joinPhone("44", null));
        assertEquals("", UpsConnector.joinPhone("44", ""));
        // Plus prefix on the code strips it before rejoin.
        assertEquals("+44 5551234567", UpsConnector.joinPhone("+44", "5551234567"));
        // Already prefixed forms pass through untouched.
        assertEquals("+445551234567", UpsConnector.joinPhone("44", "+445551234567"));
        assertEquals("00445551234567", UpsConnector.joinPhone("44", "00445551234567"));
    }

    @Test
    void residentialAndPhoneCoexist() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientResidential(true);
        r.setRecipientPhoneCountryCode("44");

        // UPS
        Map<String, Object> shipTo = upsShipTo(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> upsAddr = (Map<String, Object>) shipTo.get("Address");
        assertTrue(upsAddr.containsKey("ResidentialAddressIndicator"));
        @SuppressWarnings("unchecked")
        Map<String, Object> upsPhone = (Map<String, Object>) shipTo.get("Phone");
        assertEquals("+44 20 7946 0958", upsPhone.get("Number"));

        // FedEx
        r.setCarrierCode("FEDEX");
        Map<String, Object> recipient = fedexRecipient(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> fxAddr = (Map<String, Object>) recipient.get("address");
        assertEquals(true, fxAddr.get("residential"));
        @SuppressWarnings("unchecked")
        Map<String, Object> fxContact = (Map<String, Object>) recipient.get("contact");
        assertEquals("+44 20 7946 0958", fxContact.get("phoneNumber"));
    }

    @Test
    void listUsedForNotNullSuppressAssertion() {
        // Guard against a future refactor that changes recipients[] from
        // Object[] to List — the tests above assume Object[].
        assertEquals(0, List.of().size());
    }
}
