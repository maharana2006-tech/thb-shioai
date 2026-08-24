package com.multiship.backend.service.carriers;

import com.multiship.backend.dto.PackageDetailDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link StampsConnector#weightInOz} — the
 * package-weight-to-ounces converter used when building SWSIM Rate and
 * CreateIndicium envelopes.
 *
 * <p>Origin: pre-fix, {@code weightInOz} fell back to the literal
 * string {@code "0"} when {@code UnitConverter.toOunces} returned null
 * (unrecognised unit, null weight). SWSIM would then either quote a
 * bogus near-free rate for a 0-oz shipment or reject with a confusing
 * error further down. Classic silent-fallback that the codebase
 * actively hunts (see PRs #410-#414 for the 5-batch silent-fallback
 * audit). Post-fix throws {@link IllegalArgumentException} with a
 * diagnosable message so the caller sees the real problem.
 */
class StampsWeightConversionTest {

    private static Method weightInOz() throws Exception {
        Method m = StampsConnector.class.getDeclaredMethod("weightInOz", PackageDetailDTO.class);
        m.setAccessible(true);
        return m;
    }

    private static String invoke(PackageDetailDTO pkg) throws Throwable {
        try {
            return (String) weightInOz().invoke(null, pkg);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // ===== happy path =====

    @Test
    void validPound_convertsToOunces() throws Throwable {
        PackageDetailDTO p = PackageDetailDTO.builder()
                .weight(new BigDecimal("2.5")).weightUnit("LB").build();
        // 2.5 lb = 40 oz. UnitConverter returns scale-2; assert on the
        // numeric value rather than string exact-match to survive any
        // future scale change.
        BigDecimal oz = new BigDecimal(invoke(p));
        assertEquals(0, new BigDecimal("40.00").compareTo(oz),
                "2.5 lb should equal 40.00 oz; got " + oz);
    }

    @Test
    void validKilogram_convertsToOunces() throws Throwable {
        PackageDetailDTO p = PackageDetailDTO.builder()
                .weight(new BigDecimal("1")).weightUnit("KG").build();
        // 1 kg ≈ 35.274 oz — verify the answer's within a plausible range.
        String result = invoke(p);
        BigDecimal oz = new BigDecimal(result);
        assertTrue(oz.compareTo(new BigDecimal("35.0")) >= 0
                && oz.compareTo(new BigDecimal("36.0")) < 0,
                "1 kg should be ~35.27 oz; got " + result);
    }

    // ===== fail-loud on inputs that used to silently return "0" =====

    @Test
    void nullWeight_throwsInsteadOfSilentZero() throws Throwable {
        PackageDetailDTO p = PackageDetailDTO.builder()
                .weight(null).weightUnit("LB").build();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> invoke(p));
        assertTrue(ex.getMessage().contains("convert"),
                "message must explain what failed; got: " + ex.getMessage());
    }

    @Test
    void unrecognisedUnit_throwsInsteadOfSilentZero() throws Throwable {
        PackageDetailDTO p = PackageDetailDTO.builder()
                .weight(new BigDecimal("5")).weightUnit("STONE").build();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> invoke(p));
        assertTrue(ex.getMessage().contains("STONE"),
                "message must name the offending unit; got: " + ex.getMessage());
    }

}
