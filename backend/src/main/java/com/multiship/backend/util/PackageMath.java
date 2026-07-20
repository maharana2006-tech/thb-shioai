package com.multiship.backend.util;

import com.multiship.backend.model.PackagePreset;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Carrier billing math for packages.
 *
 * DIMENSIONAL WEIGHT: carriers bill max(actual, L×W×H ÷ divisor) — divisor
 * 139 for in→lb (UPS/FedEx), 5000 for cm→kg. Since Aug 2025 every dimension
 * is rounded UP to the whole inch/cm before the formula.
 *
 * OVERSIZE: length + girth (girth = 2×width + 2×height, on the two shorter
 * sides) > 130 in triggers the Large Package/Oversize surcharge ($220+, 90 lb
 * minimum billable); > 165 in L+G, > 108 in length, or > 150 lb cannot ship
 * as a parcel at all (freight).
 */
public final class PackageMath {

    public enum OversizeStatus { OK, SURCHARGE, OVER_MAX }

    private static final BigDecimal DIVISOR_IN_LB = new BigDecimal("139");
    private static final BigDecimal DIVISOR_CM_KG = new BigDecimal("5000");
    private static final BigDecimal LB_PER_KG = new BigDecimal("2.20462");
    private static final BigDecimal IN_PER_CM = new BigDecimal("0.393701");

    private PackageMath() {}

    /**
     * Dimensional weight of a box in the preset's own weight unit; null when
     * dimensions are missing (carrier packaging without dims) or the box is
     * flat-rate (flat-rate ignores DIM by definition).
     */
    public static BigDecimal dimWeight(PackagePreset p) {
        if (p == null || p.getLength() == null || p.getWidth() == null || p.getHeight() == null) return null;
        if (Boolean.TRUE.equals(p.getFlatRate())) return null;
        boolean cm = "CM".equalsIgnoreCase(p.getDimUnit());
        // carriers round every dimension UP before multiplying
        BigDecimal volume = p.getLength().setScale(0, RoundingMode.CEILING)
                .multiply(p.getWidth().setScale(0, RoundingMode.CEILING))
                .multiply(p.getHeight().setScale(0, RoundingMode.CEILING));
        BigDecimal dim = volume.divide(cm ? DIVISOR_CM_KG : DIVISOR_IN_LB, 2, RoundingMode.HALF_UP);
        // dim result unit: in→lb, cm→kg; convert if the preset weighs in the other unit
        boolean weighsKg = "KG".equalsIgnoreCase(p.getWeightUnit());
        if (cm && !weighsKg) return dim.multiply(LB_PER_KG).setScale(2, RoundingMode.HALF_UP);
        if (!cm && weighsKg) return dim.divide(LB_PER_KG, 2, RoundingMode.HALF_UP);
        return dim;
    }

    /**
     * What the carrier bills: max(actual + tare, dimensional weight).
     * Flat-rate boxes bill the flat rate regardless — actual+tare returned.
     */
    public static BigDecimal billableWeight(PackagePreset p, BigDecimal actualWeight) {
        BigDecimal actual = actualWeight != null ? actualWeight : BigDecimal.ONE;
        if (p == null) return actual;
        if (p.getTareWeight() != null) actual = actual.add(p.getTareWeight());
        BigDecimal dim = dimWeight(p);
        return dim != null && dim.compareTo(actual) > 0 ? dim : actual;
    }

    /** Length + girth in INCHES (girth = 2×width + 2×height); null without dims. */
    public static BigDecimal lengthPlusGirthInches(PackagePreset p) {
        if (p == null || p.getLength() == null || p.getWidth() == null || p.getHeight() == null) return null;
        BigDecimal lpg = p.getLength().add(p.getWidth().add(p.getHeight()).multiply(new BigDecimal(2)));
        if ("CM".equalsIgnoreCase(p.getDimUnit())) lpg = lpg.multiply(IN_PER_CM);
        return lpg.setScale(1, RoundingMode.HALF_UP);
    }

    /** Surcharge status from the carrier oversize thresholds. */
    public static OversizeStatus oversizeStatus(PackagePreset p) {
        BigDecimal lpg = lengthPlusGirthInches(p);
        if (lpg == null) return OversizeStatus.OK;
        BigDecimal lengthIn = "CM".equalsIgnoreCase(p.getDimUnit())
                ? p.getLength().multiply(IN_PER_CM) : p.getLength();
        if (lpg.compareTo(new BigDecimal("165")) > 0 || lengthIn.compareTo(new BigDecimal("108")) > 0) {
            return OversizeStatus.OVER_MAX;
        }
        if (lpg.compareTo(new BigDecimal("130")) > 0) {
            return OversizeStatus.SURCHARGE;
        }
        return OversizeStatus.OK;
    }
}
