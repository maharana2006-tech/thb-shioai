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
 * Sprint 10 tests — {@code recipientAddressLine3} threads through UPS,
 * FedEx, and DHL payloads. Kept in one file so the "how does line3 appear
 * on each carrier's wire" story is discoverable in one place.
 *
 * <p>USPS/Stamps is not covered here — SWSIM Address1 / Address2 doesn't
 * have an Address3 element on the CreateIndicium call; adding line3 to
 * SWSIM would require concatenating line2 + line3 with a space, which is
 * deferred to a future SWSIM-specific PR.
 */
class AddressLine3Test {

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
                .shipperName("Acme").shipperPhone("5551234567")
                .shipperAddressLine1("1 Way").shipperCity("Louisville")
                .shipperState("KY").shipperPostalCode("40209").shipperCountryCode("US")
                .recipientName("Jane").recipientPhone("5559876543")
                .recipientAddressLine1("Building 3-15")
                .recipientAddressLine2("Apt 42")
                .recipientCity("Tokyo").recipientState("").recipientPostalCode("100-0001").recipientCountryCode("JP")
                .referenceNumber("PO-1")
                .build();
    }

    /* -------------------------- UPS -------------------------- */

    @SuppressWarnings("unchecked")
    private List<String> upsRecipientLines(ShipmentRequestDTO r) throws Exception {
        UpsConnector c = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        Method m = UpsConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(c, r);
        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                payload.get("ShipmentRequest")).get("Shipment");
        Map<String, Object> shipTo = (Map<String, Object>) shipment.get("ShipTo");
        Map<String, Object> address = (Map<String, Object>) shipTo.get("Address");
        return (List<String>) address.get("AddressLine");
    }

    @Test
    void upsAppendsLine3WhenPresent() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientAddressLine3("Chiyoda-ku");
        List<String> lines = upsRecipientLines(r);
        assertEquals(3, lines.size(), "UPS AddressLine[] should have 3 entries");
        assertEquals("Building 3-15", lines.get(0));
        assertEquals("Apt 42", lines.get(1));
        assertEquals("Chiyoda-ku", lines.get(2));
    }

    @Test
    void upsOmitsLine3WhenBlank() throws Exception {
        List<String> lines = upsRecipientLines(baseRequest());
        assertEquals(2, lines.size(),
                "Missing line3 should not create an empty element in AddressLine[]");
    }

    @Test
    void upsSkipsLine3IfLine2Blank() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientAddressLine2(null);
        r.setRecipientAddressLine3("Chiyoda-ku");
        List<String> lines = upsRecipientLines(r);
        // AddressLine[] should be [line1, line3] — blanks don't get slot
        assertEquals(2, lines.size(),
                "Blank line2 shouldn't create a gap between line1 and line3");
        assertEquals("Building 3-15", lines.get(0));
        assertEquals("Chiyoda-ku", lines.get(1));
    }

    /* -------------------------- FedEx -------------------------- */

    @SuppressWarnings("unchecked")
    private List<String> fedexRecipientLines(ShipmentRequestDTO r) throws Exception {
        CarrierProperties props = new CarrierProperties();
        props.getFedEx().setLabelResponseOption("URL_ONLY");
        FedExConnector c = new FedExConnector(props, new ObjectMapper(), noFx());
        Method m = FedExConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(c, r);
        Map<String, Object> rs = (Map<String, Object>) payload.get("requestedShipment");
        Object[] recipients = (Object[]) rs.get("recipients");
        Map<String, Object> recipient = (Map<String, Object>) recipients[0];
        Map<String, Object> address = (Map<String, Object>) recipient.get("address");
        Object raw = address.get("streetLines");
        if (raw instanceof String[]) return List.of((String[]) raw);
        return (List<String>) raw;
    }

    @Test
    void fedexAppendsLine3WhenPresent() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientAddressLine3("Chiyoda-ku");
        List<String> lines = fedexRecipientLines(r);
        assertEquals(3, lines.size());
        assertEquals("Chiyoda-ku", lines.get(2));
    }

    @Test
    void fedexOmitsLine3WhenBlank() throws Exception {
        List<String> lines = fedexRecipientLines(baseRequest());
        assertEquals(2, lines.size(),
                "streetLines should not have empty trailing entries");
    }

    /* -------------------------- DHL -------------------------- */

    @SuppressWarnings("unchecked")
    private Map<String, Object> dhlReceiverAddress(ShipmentRequestDTO r) throws Exception {
        DhlConnector c = new DhlConnector(new CarrierProperties(), new ObjectMapper());
        Method m = DhlConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        Map<String, Object> payload = (Map<String, Object>) m.invoke(c, r);
        Map<String, Object> details = (Map<String, Object>) payload.get("customerDetails");
        Map<String, Object> receiver = (Map<String, Object>) details.get("receiverDetails");
        return (Map<String, Object>) receiver.get("postalAddress");
    }

    @Test
    void dhlEmitsAddressLine3AsSeparateField() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientAddressLine3("Chiyoda-ku");
        Map<String, Object> addr = dhlReceiverAddress(r);
        assertEquals("Building 3-15", addr.get("addressLine1"));
        assertEquals("Apt 42", addr.get("addressLine2"));
        assertEquals("Chiyoda-ku", addr.get("addressLine3"),
                "DHL exposes a named addressLine3 field, not a lines array");
    }

    @Test
    void dhlOmitsAddressLine3WhenBlank() throws Exception {
        Map<String, Object> addr = dhlReceiverAddress(baseRequest());
        assertNull(addr.get("addressLine3"),
                "Blank line3 should be omitted from the postalAddress block");
    }

    @Test
    void dhlHandlesBlankLine2Correctly() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientAddressLine2(null);
        r.setRecipientAddressLine3("Chiyoda-ku");
        Map<String, Object> addr = dhlReceiverAddress(r);
        // Collapses to addressLine1 + addressLine2 (which holds line3) — the
        // DHL builder squashes blanks so we don't emit an empty middle slot.
        assertEquals("Building 3-15", addr.get("addressLine1"));
        assertEquals("Chiyoda-ku", addr.get("addressLine2"));
        assertNull(addr.get("addressLine3"),
                "Squashed layout should collapse the gap, not preserve line3 slot");
    }

    /* -------------------------- USPS/Stamps (Sprint 11) -------------------------- */

    /** Build a SWSIM envelope and return the {@code <To>...</To>} section as raw XML. */
    private String uspsRecipientBlock(ShipmentRequestDTO r) throws Exception {
        StampsConnector c = new StampsConnector(new CarrierProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper());
        java.lang.reflect.Method m = StampsConnector.class.getDeclaredMethod(
                "buildCreateIndiciumEnvelope", ShipmentRequestDTO.class, String.class);
        m.setAccessible(true);
        String soap = (String) m.invoke(c, r, "AUTH-TOKEN");
        // Return the first <To>...</To> block AFTER the Rate block (the Rate
        // block has its own <To> with just ZIP + Country).
        int rateEnd = soap.indexOf("</Rate>");
        int toOpen = soap.indexOf("<To>", rateEnd);
        int toClose = soap.indexOf("</To>", toOpen);
        return soap.substring(toOpen, toClose + "</To>".length());
    }

    @Test
    void uspsConcatenatesLine3IntoAddress2() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientAddressLine3("Chiyoda-ku");
        String toBlock = uspsRecipientBlock(r);
        assertTrue(toBlock.contains("<Address1>Building 3-15</Address1>"),
                "line1 → Address1");
        assertTrue(toBlock.contains("<Address2>Apt 42 Chiyoda-ku</Address2>"),
                "line2 + line3 concatenated with a single space into Address2");
    }

    @Test
    void uspsEmitsLine2AloneWhenNoLine3() throws Exception {
        String toBlock = uspsRecipientBlock(baseRequest());
        assertTrue(toBlock.contains("<Address2>Apt 42</Address2>"),
                "line2 alone survives unchanged");
    }

    @Test
    void uspsBlankLine2WithLine3EmitsLine3AsAddress2() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientAddressLine2(null);
        r.setRecipientAddressLine3("Chiyoda-ku");
        String toBlock = uspsRecipientBlock(r);
        assertTrue(toBlock.contains("<Address2>Chiyoda-ku</Address2>"),
                "blank line2 + line3 puts line3 alone into Address2");
    }

    @Test
    void uspsBothLine2AndLine3BlankOmitsAddress2() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientAddressLine2(null);
        String toBlock = uspsRecipientBlock(r);
        assertFalse(toBlock.contains("<Address2>"),
                "blank line2 + blank line3 should omit Address2 entirely");
    }

    @Test
    void joinSwsimAddress2CoversAllFourCases() {
        assertEquals("Apt 42 Chiyoda-ku", StampsConnector.joinSwsimAddress2("Apt 42", "Chiyoda-ku"));
        assertEquals("Apt 42", StampsConnector.joinSwsimAddress2("Apt 42", null));
        assertEquals("Chiyoda-ku", StampsConnector.joinSwsimAddress2(null, "Chiyoda-ku"));
        assertEquals("", StampsConnector.joinSwsimAddress2(null, null));
        assertEquals("", StampsConnector.joinSwsimAddress2("  ", "  "));
        // Trim both sides so the space between them is exactly one.
        assertEquals("Apt 42 Chiyoda-ku",
                StampsConnector.joinSwsimAddress2("  Apt 42  ", "  Chiyoda-ku  "));
    }

    /* -------------------------- Cross-carrier consistency -------------------------- */

    @Test
    void allFourCarriersEmitLine3ForTheSameRequest() throws Exception {
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientAddressLine3("Chiyoda-ku");

        assertTrue(upsRecipientLines(r).contains("Chiyoda-ku"),
                "UPS payload should carry line3 as an AddressLine[] entry");
        assertTrue(fedexRecipientLines(r).contains("Chiyoda-ku"),
                "FedEx payload should carry line3 as a streetLines[] entry");
        assertEquals("Chiyoda-ku", dhlReceiverAddress(r).get("addressLine3"),
                "DHL payload should carry line3 as its own addressLine3 field");
        assertTrue(uspsRecipientBlock(r).contains("Chiyoda-ku"),
                "USPS/SWSIM should carry line3 concatenated into Address2");
    }

    @Test
    void noneOfTheCarriersLeakLine3ToShipper() throws Exception {
        // Only recipient line3 is threaded — shipper stays as before.
        ShipmentRequestDTO r = baseRequest();
        r.setRecipientAddressLine3("Chiyoda-ku");

        UpsConnector ups = new UpsConnector(new CarrierProperties(), new ObjectMapper());
        Method m = UpsConnector.class.getDeclaredMethod("buildShipmentPayload", ShipmentRequestDTO.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) m.invoke(ups, r);
        @SuppressWarnings("unchecked")
        Map<String, Object> shipment = (Map<String, Object>) ((Map<String, Object>)
                payload.get("ShipmentRequest")).get("Shipment");
        @SuppressWarnings("unchecked")
        Map<String, Object> shipper = (Map<String, Object>) shipment.get("Shipper");
        @SuppressWarnings("unchecked")
        Map<String, Object> shipperAddress = (Map<String, Object>) shipper.get("Address");
        @SuppressWarnings("unchecked")
        List<String> shipperLines = (List<String>) shipperAddress.get("AddressLine");
        assertFalse(shipperLines.contains("Chiyoda-ku"),
                "Shipper's AddressLine[] should not carry the recipient's line3");
    }
}
