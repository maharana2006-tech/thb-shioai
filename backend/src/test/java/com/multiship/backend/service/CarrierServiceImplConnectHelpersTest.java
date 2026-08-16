package com.multiship.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 53 /settings/carriers page-tests (slice: BE-svc-impl-connect helpers).
 *
 * <p>Coverage focus: the deterministic private helpers that the
 * {@code connectToCarrier} flow relies on for canonical / legacy code
 * resolution, environment normalization, token masking, and defensive
 * string truncation. Every helper here is a pure function of its inputs
 * — no repository, no connector, no Spring context — so the whole file
 * runs in ~50ms with zero mock setup.
 *
 * <p>Why here and not in {@link CarrierServiceImplTest}: that class is
 * a placeholder deliberately keeping the ~30-collaborator harness for a
 * future sprint (see its Javadoc). These pure-helper tests were the
 * missing coverage from that placeholder that CAN be written today
 * without inflating the diff by hundreds of lines of Mockito setup.
 *
 * <p>The helpers under test are all {@code private} on
 * {@link CarrierServiceImpl}; we invoke them by reflection so the
 * production visibility stays package-encapsulated (the connect flow
 * is the only intended caller).
 */
class CarrierServiceImplConnectHelpersTest {

    /**
     * Bare {@link CarrierServiceImpl} instance — the reflected helpers
     * touch no injected fields, so we can side-step the 30-collaborator
     * Lombok constructor by allocating via a nullary-arg ctor equivalent.
     * The impl's fields are final (Lombok), so we use ReflectionTestUtils
     * on newInstance via Objenesis-free path: construct via the generated
     * all-args constructor with nulls (unused for the helpers we call).
     */
    private static Object invoke(String method, Class<?>[] types, Object... args) {
        try {
            Method m = CarrierServiceImpl.class.getDeclaredMethod(method, types);
            m.setAccessible(true);
            // The helpers we call don't touch instance fields, so a
            // sacrificial instance with `null` collaborators is safe.
            CarrierServiceImpl target = allocate();
            return m.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Reflection failed for " + method, e);
        }
    }

    private static CarrierServiceImpl allocate() {
        try {
            // Use Unsafe-free allocation: sun.reflect not portable across
            // JDK vendors. Instead, use ReflectionTestUtils convention —
            // Lombok generates a single all-args constructor for @RequiredArgsConstructor
            // over final fields. We pass nulls; helpers under test never
            // dereference them.
            java.lang.reflect.Constructor<?>[] ctors = CarrierServiceImpl.class.getDeclaredConstructors();
            java.lang.reflect.Constructor<?> ctor = ctors[0];
            ctor.setAccessible(true);
            Object[] args = new Object[ctor.getParameterCount()];
            return (CarrierServiceImpl) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to allocate CarrierServiceImpl for reflection tests", e);
        }
    }

    // ==================================================================
    // resolveCanonicalCarrierCode — API-facing vocabulary UPS/FEDEX/USPS
    // with legacy P80/F77/L01 tolerated on input.
    // ==================================================================

    @Test
    void resolveCanonicalCarrierCode_legacyP80_mapsToUps() {
        Object out = invoke("resolveCanonicalCarrierCode", new Class[]{String.class}, "P80");
        assertEquals("UPS", out);
    }

    @Test
    void resolveCanonicalCarrierCode_legacyF77_mapsToFedex() {
        Object out = invoke("resolveCanonicalCarrierCode", new Class[]{String.class}, "F77");
        assertEquals("FEDEX", out);
    }

    @Test
    void resolveCanonicalCarrierCode_legacyL01_mapsToUsps() {
        Object out = invoke("resolveCanonicalCarrierCode", new Class[]{String.class}, "L01");
        assertEquals("USPS", out);
    }

    @Test
    void resolveCanonicalCarrierCode_canonicalUpsPassesThroughUppercased() {
        Object out = invoke("resolveCanonicalCarrierCode", new Class[]{String.class}, "ups");
        assertEquals("UPS", out);
    }

    @Test
    void resolveCanonicalCarrierCode_unknownCodePassesThroughUppercased() {
        // Documented behaviour — unknown codes are NOT rejected here;
        // the connector lookup downstream is what fails on unknown carriers.
        Object out = invoke("resolveCanonicalCarrierCode", new Class[]{String.class}, "dhl");
        assertEquals("DHL", out);
    }

    // ==================================================================
    // toShipViaCode — internal persistence code (ship_vias table)
    // ==================================================================

    @Test
    void toShipViaCode_upsMapsToP80() {
        Object out = invoke("toShipViaCode", new Class[]{String.class}, "UPS");
        assertEquals("P80", out);
    }

    @Test
    void toShipViaCode_fedexMapsToF77() {
        Object out = invoke("toShipViaCode", new Class[]{String.class}, "FEDEX");
        assertEquals("F77", out);
    }

    @Test
    void toShipViaCode_uspsMapsToL01() {
        Object out = invoke("toShipViaCode", new Class[]{String.class}, "USPS");
        assertEquals("L01", out);
    }

    @Test
    void toShipViaCode_unknownCanonicalPassesThroughUppercased() {
        Object out = invoke("toShipViaCode", new Class[]{String.class}, "dhl");
        assertEquals("DHL", out);
    }

    @Test
    void toShipViaCode_isCaseInsensitiveOnInput() {
        Object out = invoke("toShipViaCode", new Class[]{String.class}, "ups");
        assertEquals("P80", out);
    }

    // ==================================================================
    // normalizeEnvironment — persists as UPPERCASE, else falls back to
    // the app-wide default from CarrierProperties.
    // ==================================================================

    @Test
    void normalizeEnvironment_textInputUppercased() {
        Object out = invoke("normalizeEnvironment",
                new Class[]{String.class, String.class}, "sandbox", "PRODUCTION");
        assertEquals("SANDBOX", out);
    }

    @Test
    void normalizeEnvironment_blankInputFallsBackToDefault() {
        Object out = invoke("normalizeEnvironment",
                new Class[]{String.class, String.class}, "   ", "PRODUCTION");
        assertEquals("PRODUCTION", out);
    }

    @Test
    void normalizeEnvironment_nullInputFallsBackToDefault() {
        Object out = invoke("normalizeEnvironment",
                new Class[]{String.class, String.class}, null, "PRODUCTION");
        assertEquals("PRODUCTION", out);
    }

    // ==================================================================
    // maskToken — >8 chars → prefix4...suffix4; short/blank passthrough.
    // ==================================================================

    @Test
    void maskToken_longTokenIsMaskedPrefixSuffix() {
        // 20-char token: "abcd" + "..." + "wxyz"
        Object out = invoke("maskToken", new Class[]{String.class}, "abcdefghijklmnopwxyz");
        assertEquals("abcd...wxyz", out);
    }

    @Test
    void maskToken_shortTokenPassesThrough() {
        // ≤8 chars: no masking (no length safety margin to mask around).
        Object out = invoke("maskToken", new Class[]{String.class}, "12345678");
        assertEquals("12345678", out);
    }

    @Test
    void maskToken_nullTokenPassesThrough() {
        Object out = invoke("maskToken", new Class[]{String.class}, (Object) null);
        assertNull(out);
    }

    @Test
    void maskToken_blankTokenPassesThrough() {
        Object out = invoke("maskToken", new Class[]{String.class}, "   ");
        assertEquals("   ", out);
    }

    @Test
    void maskToken_boundaryNineCharsIsMasked() {
        // Exactly one over the ≤8 cutoff — first masked token.
        Object out = invoke("maskToken", new Class[]{String.class}, "123456789");
        assertEquals("1234...6789", out);
    }

    // ==================================================================
    // truncate — safe VARCHAR-column length clamp used by persistCarrierDetails.
    // ==================================================================

    @Test
    void truncate_shortValuePassesThrough() {
        Object out = invoke("truncate", new Class[]{String.class, int.class}, "abc", 10);
        assertEquals("abc", out);
    }

    @Test
    void truncate_exactLengthPassesThrough() {
        Object out = invoke("truncate", new Class[]{String.class, int.class}, "abcdef", 6);
        assertEquals("abcdef", out);
    }

    @Test
    void truncate_longValueIsClamped() {
        Object out = invoke("truncate", new Class[]{String.class, int.class}, "abcdefghij", 5);
        assertEquals("abcde", out);
    }

    @Test
    void truncate_nullValuePassesThrough() {
        Object out = invoke("truncate", new Class[]{String.class, int.class}, null, 10);
        assertNull(out);
    }

    @Test
    void truncate_blankValuePassesThrough() {
        Object out = invoke("truncate", new Class[]{String.class, int.class}, "   ", 2);
        // hasText==false → returned as-is regardless of length cap.
        assertEquals("   ", out);
    }

    // ==================================================================
    // Round-trip sanity: canonical → shipVia → canonical is stable.
    // ==================================================================

    @Test
    void codeRoundTrip_canonicalToShipViaAndBack_isStable_forAllKnownCarriers() {
        for (String canonical : new String[]{"UPS", "FEDEX", "USPS"}) {
            Object shipVia = invoke("toShipViaCode", new Class[]{String.class}, canonical);
            Object back = invoke("resolveCanonicalCarrierCode", new Class[]{String.class}, shipVia.toString());
            assertEquals(canonical, back, "round-trip failed for canonical=" + canonical);
        }
    }

    @Test
    void codeRoundTrip_legacyShipViaCodesAreTolerated() {
        // Verify the P80/F77/L01 tolerance is symmetric — a legacy code
        // resolves to canonical, which maps back to the same legacy code.
        assertEquals("P80", invoke("toShipViaCode", new Class[]{String.class},
                (String) invoke("resolveCanonicalCarrierCode", new Class[]{String.class}, "P80")));
        assertEquals("F77", invoke("toShipViaCode", new Class[]{String.class},
                (String) invoke("resolveCanonicalCarrierCode", new Class[]{String.class}, "F77")));
        assertEquals("L01", invoke("toShipViaCode", new Class[]{String.class},
                (String) invoke("resolveCanonicalCarrierCode", new Class[]{String.class}, "L01")));
    }

    @Test
    void anchorTest_carrierServiceImplClassIsReflectivelyReachable() {
        // Sentinel — if the class disappears/renames, every test above
        // fails opaquely. This gives one clear signal instead.
        assertTrue(CarrierServiceImpl.class.getName().endsWith("CarrierServiceImpl"));
    }
}
