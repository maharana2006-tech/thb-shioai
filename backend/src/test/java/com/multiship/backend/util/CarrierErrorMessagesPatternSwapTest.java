package com.multiship.backend.util;

import com.multiship.backend.util.CarrierErrorMessages.PatternRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** V118 — DB-driven pattern swap honoured; bootstrap preserved on
 *  null/empty input; {carrier} substitution works. */
class CarrierErrorMessagesPatternSwapTest {

    @AfterEach
    void restoreBootstrap() {
        // Each test may swap the volatile list; restore a known default by
        // re-applying the same bootstrap pattern that was there pre-V118.
        CarrierErrorMessages.setPatternRules(List.of(
                new PatternRule("POSTAL|ZIP",
                        "{carrier} rejected the postal code for this address.")));
    }

    @Test
    void bootstrapHandlesPostalMatch() {
        String out = CarrierErrorMessages.humanize("USPS HTTP 400: ZIP code invalid", "USPS");
        assertEquals("USPS rejected the postal code for this address.", out);
    }

    @Test
    void swappedRuleHandlesNewToken() {
        CarrierErrorMessages.setPatternRules(List.of(
                new PatternRule("WEIGHT.OVER|MAX WEIGHT",
                        "{carrier} refused the package — weight exceeds the service limit.")));
        String out = CarrierErrorMessages.humanize("UPS HTTP 400: WEIGHT.OVER_MAX", "UPS");
        assertEquals("UPS refused the package — weight exceeds the service limit.", out);
    }

    @Test
    void nullRulesPreservePriorList() {
        CarrierErrorMessages.setPatternRules(List.of(
                new PatternRule("MARKER", "{carrier} marker hit.")));
        CarrierErrorMessages.setPatternRules(null);  // no-op
        String out = CarrierErrorMessages.humanize("FEDEX HTTP 400: MARKER present", "FEDEX");
        assertEquals("FedEx marker hit.", out);
    }

    @Test
    void emptyRulesPreservePriorList() {
        CarrierErrorMessages.setPatternRules(List.of(
                new PatternRule("MARKER", "{carrier} marker hit.")));
        CarrierErrorMessages.setPatternRules(List.of());  // no-op
        String out = CarrierErrorMessages.humanize("UPS HTTP 400: MARKER", "UPS");
        assertEquals("UPS marker hit.", out);
    }

    @Test
    void unmatchedPayloadFallsThroughToReasonExtraction() {
        String out = CarrierErrorMessages.humanize(
                "FEDEX HTTP 400: {\"message\":\"Something unexpected went wrong\"}", "FEDEX");
        assertTrue(out.contains("FedEx rejected this shipment"), out);
        assertTrue(out.contains("Something unexpected went wrong"), out);
    }
}
