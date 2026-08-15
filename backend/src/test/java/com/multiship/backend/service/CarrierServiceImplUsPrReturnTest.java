package com.multiship.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sprint 52 follow-up — direct coverage of the US <-> PR return route
 * detection helper on {@link CarrierServiceImpl}. Kept in its own class
 * (rather than {@link CarrierServiceImplTest}, which is a placeholder
 * documenting why a full CarrierServiceImpl fixture isn't wired yet) so
 * that adding the ~30-collaborator Mockito harness in a future sprint
 * doesn't force a rebase of these small assertions.
 *
 * <p>Rule: swap the resolver's serviceType to {@code RETURN_US_PR} when
 * (carrier=UPS AND direction=RETURN AND {origin,dest} == {US,PR}).
 * Everything else passes through unchanged.
 */
class CarrierServiceImplUsPrReturnTest {

    private static final String ORIG_SVC = "GROUND";

    @Test
    void usToPrReturnUps_swapsToReturnUsPr() {
        assertEquals("RETURN_US_PR",
                CarrierServiceImpl.maybeUpsUsPrReturnService("UPS", "RETURN", "US", "PR", ORIG_SVC));
    }

    @Test
    void prToUsReturnUps_swapsToReturnUsPr_biDirectional() {
        // "US_PR_ORIGIN_RETURN" spec: either endpoint can be origin.
        assertEquals("RETURN_US_PR",
                CarrierServiceImpl.maybeUpsUsPrReturnService("UPS", "RETURN", "PR", "US", ORIG_SVC));
    }

    @Test
    void usToPrForwardUps_passesThrough() {
        // Forward direction: 200-pkg cap still applies, no override.
        assertEquals(ORIG_SVC,
                CarrierServiceImpl.maybeUpsUsPrReturnService("UPS", "FORWARD", "US", "PR", ORIG_SVC));
    }

    @Test
    void usToCaReturnUps_passesThrough() {
        // US -> CA return uses the generic 20-pkg RETURN cap.
        assertEquals(ORIG_SVC,
                CarrierServiceImpl.maybeUpsUsPrReturnService("UPS", "RETURN", "US", "CA", ORIG_SVC));
    }

    @Test
    void usToPrReturnFedEx_passesThrough_notUpsSpecificRule() {
        // Rule only applies to UPS; other carriers get their own resolver row.
        assertEquals(ORIG_SVC,
                CarrierServiceImpl.maybeUpsUsPrReturnService("FEDEX", "RETURN", "US", "PR", ORIG_SVC));
    }

    @Test
    void nullCountries_passThrough() {
        assertEquals(ORIG_SVC,
                CarrierServiceImpl.maybeUpsUsPrReturnService("UPS", "RETURN", null, "PR", ORIG_SVC));
        assertEquals(ORIG_SVC,
                CarrierServiceImpl.maybeUpsUsPrReturnService("UPS", "RETURN", "US", null, ORIG_SVC));
    }

    @Test
    void caseInsensitive_carrierAndDirectionAndCountries() {
        assertEquals("RETURN_US_PR",
                CarrierServiceImpl.maybeUpsUsPrReturnService("ups", "return", "us", "pr", ORIG_SVC));
    }
}
