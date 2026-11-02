package com.multiship.backend.service.carriers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression — SERA rejected {@code "residential"} with VALIDATION 800000
 * ("The residential_indicator specified is invalid") on every residential
 * manual USPS label, after rating had already passed.
 */
class StampsSeraResidentialIndicatorTest {

    @Test
    void residential_flag_maps_to_the_yes_no_enum_and_unknown_is_omitted() {
        assertEquals("yes", StampsConnector.seraResidentialIndicator(Boolean.TRUE));
        assertEquals("no", StampsConnector.seraResidentialIndicator(Boolean.FALSE));
        assertNull(StampsConnector.seraResidentialIndicator(null));
    }
}
