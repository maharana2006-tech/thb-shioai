package com.multiship.backend.service.carriers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the residential-required service list. Coverage focuses
 * on the invariants every layer relies on: case-insensitivity,
 * null-safety, and the exact set of services that fire the check.
 */
class ResidentialRequiredServicesTest {

    @Test
    void fedexGroundHomeDeliveryRequiresResidential() {
        assertTrue(ResidentialRequiredServices.requiresResidential("GROUND_HOME_DELIVERY"));
    }

    @Test
    void requiresResidentialIsCaseInsensitive() {
        // Import rows sometimes arrive lowercased; the check must not
        // trip on casing drift.
        assertTrue(ResidentialRequiredServices.requiresResidential("ground_home_delivery"));
        assertTrue(ResidentialRequiredServices.requiresResidential("Ground_Home_Delivery"));
        assertTrue(ResidentialRequiredServices.requiresResidential("  GROUND_HOME_DELIVERY  "));
    }

    @Test
    void otherCarrierServicesDoNotRequireResidential() {
        // UPS + USPS + DHL + other FedEx services all deliver to both
        // residential and commercial. Regression guard against adding
        // a service to the required list without an audit — carrier
        // rejection semantics differ per service.
        assertFalse(ResidentialRequiredServices.requiresResidential("FEDEX_GROUND"));
        assertFalse(ResidentialRequiredServices.requiresResidential("FEDEX_2_DAY"));
        assertFalse(ResidentialRequiredServices.requiresResidential("PRIORITY_OVERNIGHT"));
        assertFalse(ResidentialRequiredServices.requiresResidential("INTERNATIONAL_PRIORITY"));
        // UPS
        assertFalse(ResidentialRequiredServices.requiresResidential("03"));   // UPS Ground
        assertFalse(ResidentialRequiredServices.requiresResidential("01"));   // UPS Next Day Air
        // USPS
        assertFalse(ResidentialRequiredServices.requiresResidential("Priority"));
        // DHL
        assertFalse(ResidentialRequiredServices.requiresResidential("P"));    // DHL Express Worldwide
    }

    @Test
    void nullOrBlankServiceCodeReturnsFalse() {
        // No service picked yet is not a validation failure — the
        // shipment form is mid-completion. Only fail the check when
        // the operator has committed to a service that requires it.
        assertFalse(ResidentialRequiredServices.requiresResidential(null));
        assertFalse(ResidentialRequiredServices.requiresResidential(""));
        assertFalse(ResidentialRequiredServices.requiresResidential("   "));
    }

    @Test
    void isInconsistentDetectsCommercialHomeDelivery() {
        // Service requires residential + residential is unset/false =
        // will fail at the carrier. Both TRUE means all good; either
        // side null-and-service-not-required means don't fire the
        // check.
        assertTrue(ResidentialRequiredServices.isInconsistent("GROUND_HOME_DELIVERY", false));
        assertTrue(ResidentialRequiredServices.isInconsistent("GROUND_HOME_DELIVERY", null));
        assertFalse(ResidentialRequiredServices.isInconsistent("GROUND_HOME_DELIVERY", true));
    }

    @Test
    void isInconsistentReturnsFalseWhenServiceDoesNotRequireResidential() {
        // A regular service with residential=false is fine (commercial
        // delivery); with residential=true is also fine (residential
        // surcharge billed if the address turns out to be a residence).
        assertFalse(ResidentialRequiredServices.isInconsistent("FEDEX_GROUND", false));
        assertFalse(ResidentialRequiredServices.isInconsistent("FEDEX_GROUND", true));
        assertFalse(ResidentialRequiredServices.isInconsistent("FEDEX_GROUND", null));
    }

    @Test
    void inconsistentMessageNamesTheService() {
        // Operator needs to know WHICH service they picked when the
        // check fires so they can change it or tick the box.
        String msg = ResidentialRequiredServices.inconsistentMessage("GROUND_HOME_DELIVERY");
        assertTrue(msg.contains("GROUND_HOME_DELIVERY"), msg);
        assertTrue(msg.toLowerCase().contains("residential"), msg);
    }

    @Test
    void inconsistentMessageHandlesNullServiceCode() {
        // Defensive — the checker never passes null (isInconsistent
        // wouldn't fire on null), but the formatter must not NPE if
        // called directly by a caller who's already sure of the case.
        String msg = ResidentialRequiredServices.inconsistentMessage(null);
        assertTrue(msg.toLowerCase().contains("residential"), msg);
    }
}
