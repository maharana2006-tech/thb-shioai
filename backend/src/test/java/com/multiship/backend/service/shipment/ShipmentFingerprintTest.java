package com.multiship.backend.service.shipment;

import com.multiship.backend.dto.ManualShipmentRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fingerprint is a property of the payload's content, not its shape or its internal fields. */
class ShipmentFingerprintTest {

    private static ManualShipmentRequest req(String city, Map<String, String> custom) {
        ManualShipmentRequest r = new ManualShipmentRequest();
        ManualShipmentRequest.Address to = new ManualShipmentRequest.Address();
        to.setName("QA"); to.setCity(city); to.setCountryCode("US");
        r.setRecipient(to);
        r.setWeight(new BigDecimal("1.0"));
        r.setCarrierCode("USPS");
        r.setCustomFields(custom);
        return r;
    }

    @Test
    void same_content_same_hash_regardless_of_map_order_and_internal_fields() {
        Map<String, String> a = new LinkedHashMap<>(); a.put("po", "1"); a.put("dept", "x");
        Map<String, String> b = new LinkedHashMap<>(); b.put("dept", "x"); b.put("po", "1");
        ManualShipmentRequest r1 = req("New York", a);
        ManualShipmentRequest r2 = req("New York", b);
        r2.setInternalValidationFingerprint("something");
        r2.setInternalIdempotencyKey("key");

        assertEquals(ShipmentFingerprint.of(r1), ShipmentFingerprint.of(r2));
        assertEquals(64, ShipmentFingerprint.of(r1).length());
        assertTrue(ShipmentFingerprint.matches(ShipmentFingerprint.of(r1), r2));
    }

    @Test
    void different_content_different_hash_and_bad_input_never_matches() {
        ManualShipmentRequest r1 = req("New York", null);
        ManualShipmentRequest r2 = req("Boston", null);
        assertNotEquals(ShipmentFingerprint.of(r1), ShipmentFingerprint.of(r2));
        assertFalse(ShipmentFingerprint.matches(null, r1));
        assertFalse(ShipmentFingerprint.matches("short", r1));
        assertFalse(ShipmentFingerprint.matches(ShipmentFingerprint.of(r2), r1));
    }
}
