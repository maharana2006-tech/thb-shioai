package com.multiship.backend.service;

import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.model.Address;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** BUG-039 — the typed "Return to" block wins; the client's return address only fills blanks. */
class CarrierServiceImplReturnToTest {

    private static Address configured() {
        Address a = new Address();
        a.setName("MKL");
        a.setLine1("8191 Logistics Dr");
        a.setCity("Zeeland");
        a.setState("MI");
        a.setZip("49464");
        a.setCountry("US");
        a.setPhone("+1 555 123 6789");
        return a;
    }

    @Test
    void typedValuesAreKeptAndBlanksAreFilled() {
        ManualShipmentRequest.Address to = new ManualShipmentRequest.Address();
        to.setName("QA Return Dock");
        to.setAddressLine1("999 QA Return Way");
        to.setCity("Holland");
        to.setPostalCode("49423");
        to.setPhone("6165550123");

        CarrierServiceImpl.fillReturnToBlanks(to, configured());

        assertEquals("QA Return Dock", to.getName());
        assertEquals("999 QA Return Way", to.getAddressLine1());
        assertEquals("Holland", to.getCity());
        assertEquals("49423", to.getPostalCode());
        assertEquals("6165550123", to.getPhone());
        // Left blank by the operator → taken from the configured address.
        assertEquals("MI", to.getState());
        assertEquals("US", to.getCountryCode());
    }

    @Test
    void emptyBlockGetsTheConfiguredAddress() {
        ManualShipmentRequest.Address to = new ManualShipmentRequest.Address();
        CarrierServiceImpl.fillReturnToBlanks(to, configured());
        assertEquals("MKL", to.getName());
        assertEquals("8191 Logistics Dr", to.getAddressLine1());
        assertEquals("Zeeland", to.getCity());
        assertEquals("49464", to.getPostalCode());
    }
}
