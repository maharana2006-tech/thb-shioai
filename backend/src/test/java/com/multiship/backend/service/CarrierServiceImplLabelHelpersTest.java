package com.multiship.backend.service;

import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.model.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 53 /settings/carriers page-tests (slice: BE-svc-impl-labels helpers).
 *
 * <p>Coverage focus: the deterministic label-flow helpers that decide
 * routing / capability BEFORE the (untested here) generateLabel
 * orchestrator hands off to a live connector.
 *
 * <ul>
 *   <li>{@code isInternational(Order)} — private. Origin (shipper
 *       property) vs destination country on the order. Positive
 *       (cross-border), negative (same country), and defensive branches
 *       (blank/null on either side).</li>
 *   <li>{@code isUsPrPair(String, String)} — static private. UPS US/PR
 *       route detector; the input to the return-service swap.</li>
 *   <li>{@code maybeUpsUsPrReturnService(...)} — package-private static.
 *       Edge-case supplement to the primary
 *       {@link CarrierServiceImplUsPrReturnTest} (whitespace, blank,
 *       null-carrier defensive).</li>
 * </ul>
 *
 * <p>Kept out of {@link CarrierServiceImplTest} for the same reason as
 * its sibling helper suites: those methods can be pinned today without
 * standing up the ~30-collaborator harness the class Javadoc defers to
 * a future sprint.
 */
class CarrierServiceImplLabelHelpersTest {

    private CarrierProperties carrierProperties;
    private CarrierServiceImpl impl;

    @BeforeEach
    void setUp() throws Exception {
        carrierProperties = new CarrierProperties();
        // ShipperDefaults is instantiated eagerly by the outer class,
        // so we just fill the field we need.
        carrierProperties.getShipper().setCountryCode("US");

        impl = allocate();
        ReflectionTestUtils.setField(impl, "carrierProperties", carrierProperties);
    }

    private static CarrierServiceImpl allocate() throws Exception {
        Constructor<?>[] ctors = CarrierServiceImpl.class.getDeclaredConstructors();
        Constructor<?> ctor = ctors[0];
        ctor.setAccessible(true);
        return (CarrierServiceImpl) ctor.newInstance(new Object[ctor.getParameterCount()]);
    }

    private static Order orderTo(String destCountry) {
        Order o = new Order();
        o.setShiptoCountryCd(destCountry);
        return o;
    }

    // ==================================================================
    // isInternational
    // ==================================================================

    @Test
    void isInternational_sameCountry_isFalse() {
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isInternational", orderTo("US"));
        assertFalse(out);
    }

    @Test
    void isInternational_crossBorder_isTrue() {
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isInternational", orderTo("CA"));
        assertTrue(out);
    }

    @Test
    void isInternational_isCaseInsensitive() {
        // Origin "US", dest "us" → same country.
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isInternational", orderTo("us"));
        assertFalse(out);
    }

    @Test
    void isInternational_isWhitespaceTolerant() {
        // Trim before compare — a stray leading space on the order must
        // not flip the customs decision.
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isInternational", orderTo("  US "));
        assertFalse(out);
    }

    @Test
    void isInternational_blankDest_isFalse() {
        // No destination country on the order → not international
        // (defensive — the customs branch never fires).
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isInternational", orderTo("   "));
        assertFalse(out);
    }

    @Test
    void isInternational_nullDest_isFalse() {
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isInternational", orderTo(null));
        assertFalse(out);
    }

    @Test
    void isInternational_blankOrigin_isFalse() {
        // Misconfigured shipper (empty countryCode) MUST NOT default to
        // international — customs gating stays off until an operator
        // configures the shipper country.
        carrierProperties.getShipper().setCountryCode("");
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isInternational", orderTo("CA"));
        assertFalse(out);
    }

    @Test
    void isInternational_nullOrigin_isFalse() {
        carrierProperties.getShipper().setCountryCode(null);
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isInternational", orderTo("CA"));
        assertFalse(out);
    }

    // ==================================================================
    // isUsPrPair (static private)
    // ==================================================================

    @Test
    void isUsPrPair_usPr_isTrue() {
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isUsPrPair", "US", "PR");
        assertTrue(out);
    }

    @Test
    void isUsPrPair_prUs_isTrue_biDirectional() {
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isUsPrPair", "PR", "US");
        assertTrue(out);
    }

    @Test
    void isUsPrPair_usCa_isFalse() {
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isUsPrPair", "US", "CA");
        assertFalse(out);
    }

    @Test
    void isUsPrPair_usUs_isFalse_notAPair() {
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isUsPrPair", "US", "US");
        assertFalse(out);
    }

    @Test
    void isUsPrPair_prPr_isFalse_notAPair() {
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isUsPrPair", "PR", "PR");
        assertFalse(out);
    }

    @Test
    void isUsPrPair_nullFirst_isFalse() {
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isUsPrPair", null, "PR");
        assertFalse(out);
    }

    @Test
    void isUsPrPair_nullSecond_isFalse() {
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isUsPrPair", "US", null);
        assertFalse(out);
    }

    @Test
    void isUsPrPair_bothNull_isFalse() {
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isUsPrPair", null, null);
        assertFalse(out);
    }

    @Test
    void isUsPrPair_caseInsensitive() {
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isUsPrPair", "us", "pr");
        assertTrue(out);
    }

    @Test
    void isUsPrPair_whitespaceTolerant() {
        boolean out = ReflectionTestUtils.invokeMethod(impl, "isUsPrPair", "  us ", "  pr");
        assertTrue(out);
    }

    // ==================================================================
    // maybeUpsUsPrReturnService — edge-case supplement to
    // CarrierServiceImplUsPrReturnTest.
    // ==================================================================

    @Test
    void maybeUpsUsPrReturnService_null_carrier_passesThrough_defensive() {
        // Defensive: even though the label orchestrator resolves the
        // carrier code before calling this, a null MUST NOT NPE.
        // Documented behaviour — the method just returns the original
        // service unchanged when it can't decide.
        Throwable ex = null;
        String out = null;
        try {
            out = CarrierServiceImpl.maybeUpsUsPrReturnService(null, "RETURN", "US", "PR", "GROUND");
        } catch (Throwable t) {
            ex = t;
        }
        // Behaviour today: null carrier NPEs on equalsIgnoreCase — we
        // pin the current behaviour (either NPE or "GROUND"). Whichever
        // it is, we assert it explicitly so a refactor changing the
        // guard order breaks the build and forces a review.
        if (ex != null) {
            // Current: throws NPE because "UPS".equalsIgnoreCase(null)
            // still returns false but is called on the literal — SO NO NPE.
            // The actual call site is !"UPS".equalsIgnoreCase(carrierCode)
            // — carrierCode==null returns false → this branch is passed.
            // Then RETURN check similarly OK. Then isUsPrPair OK.
            // Then returns "RETURN_US_PR".
            // So no NPE expected today. Fail if we hit one.
            throw new AssertionError("Unexpected NPE from maybeUpsUsPrReturnService", ex);
        }
        // With null carrier: the first guard "UPS".equalsIgnoreCase(null)
        // is false → !false → true → return originalService.
        assertEquals("GROUND", out);
    }

    @Test
    void maybeUpsUsPrReturnService_null_direction_passesThrough() {
        String out = CarrierServiceImpl.maybeUpsUsPrReturnService("UPS", null, "US", "PR", "GROUND");
        assertEquals("GROUND", out);
    }

    @Test
    void maybeUpsUsPrReturnService_whitespaceCarrier_defensivePassesThrough() {
        // "  UPS  ".equalsIgnoreCase("UPS") is false → passes through.
        // The label orchestrator normalizes carrier codes before this,
        // so the method itself doesn't trim.
        String out = CarrierServiceImpl.maybeUpsUsPrReturnService("  UPS  ", "RETURN", "US", "PR", "GROUND");
        assertEquals("GROUND", out);
    }

    @Test
    void maybeUpsUsPrReturnService_upsForwardUsToPr_passesThrough() {
        // Forward direction is US/PR domestic — no override even for UPS.
        String out = CarrierServiceImpl.maybeUpsUsPrReturnService("UPS", "FORWARD", "US", "PR", "GROUND");
        assertEquals("GROUND", out);
    }

    @Test
    void maybeUpsUsPrReturnService_upsReturnUsToUs_passesThrough_notAPair() {
        // Domestic return within US — generic 20-pkg return cap applies.
        String out = CarrierServiceImpl.maybeUpsUsPrReturnService("UPS", "RETURN", "US", "US", "GROUND");
        assertEquals("GROUND", out);
    }

    @Test
    void maybeUpsUsPrReturnService_originalServicePreserved_whenNotAllConditionsMet() {
        // Even a mixed-carrier case must preserve the incoming service string.
        String out = CarrierServiceImpl.maybeUpsUsPrReturnService("FEDEX", "RETURN", "US", "PR", "PRIORITY_OVERNIGHT");
        assertEquals("PRIORITY_OVERNIGHT", out);
    }
}
