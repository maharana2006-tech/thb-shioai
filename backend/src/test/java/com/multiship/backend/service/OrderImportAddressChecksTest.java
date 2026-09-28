package com.multiship.backend.service;

import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.service.carriers.CarrierConnector.ValidateShipmentResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Address problems a bulk row must not get through, and UPS's answers in plain words. */
class OrderImportAddressChecksTest {

    private static OrderImportRowDTO usRow(String state, String zip) {
        OrderImportRowDTO r = new OrderImportRowDTO();
        r.setClientCode("DES875");
        r.setRecipientName("Ann Lee");
        r.setRecipientPhone("3125550100");
        r.setAddressLine1("233 S Wacker Dr");
        r.setCity("Chicago");
        r.setState(state);
        r.setPostalCode(zip);
        r.setCountryCode("US");
        r.setWeight(new BigDecimal("2"));
        r.setWeightUnit("LB");
        return r;
    }

    @Test
    void aZipInAnotherStateIsAnErrorOnThePostalCode() {
        assertTrue(OrderImportServiceImpl.validateRow(usRow("CA", "60606")).contains(
                "postalCode 60606 is in IL, not CA — correct the state or the ZIP"));
        assertTrue(OrderImportServiceImpl.validateRow(usRow("IL", "60606")).stream().noneMatch(e -> e.contains("is in")));
        assertTrue(OrderImportServiceImpl.validateRow(usRow("IL", "60606-1234")).stream().noneMatch(e -> e.contains("is in")));
    }

    @Test
    void zipPrefixesMapToTheirState() {
        assertEquals("IL", OrderImportServiceImpl.usStateForZip("60606"));
        assertEquals("CA", OrderImportServiceImpl.usStateForZip("90210"));
        assertEquals("NY", OrderImportServiceImpl.usStateForZip("10001"));
        assertEquals("MA", OrderImportServiceImpl.usStateForZip("02108"));
        assertEquals("DC", OrderImportServiceImpl.usStateForZip("20500"));
        assertEquals("TX", OrderImportServiceImpl.usStateForZip("79901"));
        assertNull(OrderImportServiceImpl.usStateForZip("00901"), "Puerto Rico is left to the territory rules");
        assertNull(OrderImportServiceImpl.usStateForZip("09001"), "military ZIPs are left alone");
    }

    @Test
    void poBoxesAreRecognisedButStreetsAreNot() {
        for (String s : List.of("PO Box 12", "P.O. Box 7", "p o box 9", "Post Office Box 3", "po box")) {
            assertTrue(OrderImportServiceImpl.PO_BOX.matcher(s).find(), s);
        }
        for (String s : List.of("12 Boxwood Ln", "100 Post Office Rd", "233 S Wacker Dr")) {
            assertFalse(OrderImportServiceImpl.PO_BOX.matcher(s).find(), s);
        }
    }

    @Test
    void upsAnswersBecomePlainSentencesOnTheirField() {
        var currency = new ValidateShipmentResult(false, "ERROR", "SHIPMENT", List.of(), List.of(),
                "UPS Time-in-Transit: invalid ShipmentContentsCurrencyCode", null);
        assertTrue(OrderImportServiceImpl.plainUpsError(currency).startsWith("currency — "));

        OrderImportRowDTO row = usRow("IL", "60606");
        row.setShipViaCode("U11");
        row.setServiceType("03");
        String tit = "{\"emsResponse\":{\"services\":[{\"serviceLevel\":\"1DA\"},{\"serviceLevel\":\"2DA\"}]}}";
        var notOffered = new ValidateShipmentResult(false, "NOT_FOUND", "SHIPMENT", List.of(), List.of(),
                "UPS doesn't offer service 03 on this lane. Available: ...", tit);
        List<Map<String, Object>> codes = List.of(
                Map.of("code", "U11", "carrier", "UPS", "serviceCode", "03", "serviceName", "UPS Ground", "enabled", true),
                Map.of("code", "U43", "carrier", "UPS", "serviceCode", "02", "serviceName", "UPS 2nd Day Air", "enabled", true));
        String msg = new OrderImportServiceImpl().upsLaneMessage(row, notOffered, codes);
        assertEquals("serviceType — UPS doesn't offer UPS Ground (U11) to this address. Use U43 (UPS 2nd Day Air) instead", msg);
    }
}
