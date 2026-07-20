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

    /** UPS/FedEx carrier defaults, used when a service has no explicit limits. */
    private static final ServiceLimits DEFAULT_PARCEL = new ServiceLimits(150, 108, 165, 130);

    private PackageMath() {}

    /**
     * A carrier service's package limits (inches / pounds). Any field may be
     * null = "no limit on this dimension". surchargeLengthGirthIn = the L+girth
     * at which a "Large Package"/oversize surcharge applies (null = no surcharge
     * tier, e.g. USPS charges a flat overweight fee instead).
     */
    public record ServiceLimits(Integer maxWeightLb, Integer maxLengthIn,
                                Integer maxLengthGirthIn, Integer surchargeLengthGirthIn) {}

    /**
     * The standard carrier limits for a service when none are set explicitly.
     * UPS/FedEx parcels = 150 lb, 108" length (FedEx Express 119"), 165" L+G,
     * surcharge above 130". USPS = 70 lb, no separate length cap, 108" L+G for
     * Priority tiers and 130" for Ground Advantage, no oversize surcharge tier.
     */
    public static ServiceLimits defaultLimits(String carrier, String serviceCode) {
        String c = carrier == null ? "" : carrier.trim().toUpperCase();
        String s = serviceCode == null ? "" : serviceCode.trim().toUpperCase();
        return switch (c) {
            case "USPS" -> (s.contains("PRIORITY") && !s.contains("GROUND"))
                    ? new ServiceLimits(70, null, 108, null)   // Priority Mail / Express
                    : new ServiceLimits(70, null, 130, null);  // Ground Advantage & the rest
            case "FEDEX" -> s.contains("GROUND")
                    ? new ServiceLimits(150, 108, 165, 130)
                    : new ServiceLimits(150, 119, 165, 130);   // FedEx Express reaches 119" length
            case "UPS" -> new ServiceLimits(150, 108, 165, 130);
            default -> DEFAULT_PARCEL;
        };
    }

    /** The limits a service actually enforces: its own values, filling any gap from the carrier default. */
    public static ServiceLimits limitsOf(String carrier, String serviceCode, Integer maxWeightLb,
                                         Integer maxLengthIn, Integer maxLengthGirthIn, Integer surchargeLengthGirthIn) {
        ServiceLimits d = defaultLimits(carrier, serviceCode);
        return new ServiceLimits(
                maxWeightLb != null ? maxWeightLb : d.maxWeightLb(),
                maxLengthIn != null ? maxLengthIn : d.maxLengthIn(),
                maxLengthGirthIn != null ? maxLengthGirthIn : d.maxLengthGirthIn(),
                surchargeLengthGirthIn != null ? surchargeLengthGirthIn : d.surchargeLengthGirthIn());
    }

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

    /** Surcharge status from the standard (UPS/FedEx) oversize thresholds — carrier-blind display. */
    public static OversizeStatus oversizeStatus(PackagePreset p) {
        return oversizeStatus(p, DEFAULT_PARCEL);
    }

    /**
     * Surcharge status of a box AGAINST A SPECIFIC SERVICE'S limits: OVER_MAX
     * when it exceeds the service's max length or max length+girth (cannot ship
     * on that service), SURCHARGE when it passes the service's surcharge L+G
     * threshold, else OK. A box with no dimensions (carrier packaging) is OK.
     */
    public static OversizeStatus oversizeStatus(PackagePreset p, ServiceLimits limits) {
        BigDecimal lpg = lengthPlusGirthInches(p);
        if (lpg == null || limits == null) return OversizeStatus.OK;
        BigDecimal lengthIn = "CM".equalsIgnoreCase(p.getDimUnit())
                ? p.getLength().multiply(IN_PER_CM) : p.getLength();
        boolean overGirth = limits.maxLengthGirthIn() != null
                && lpg.compareTo(new BigDecimal(limits.maxLengthGirthIn())) > 0;
        boolean overLength = limits.maxLengthIn() != null
                && lengthIn.compareTo(new BigDecimal(limits.maxLengthIn())) > 0;
        if (overGirth || overLength) {
            return OversizeStatus.OVER_MAX;
        }
        if (limits.surchargeLengthGirthIn() != null
                && lpg.compareTo(new BigDecimal(limits.surchargeLengthGirthIn())) > 0) {
            return OversizeStatus.SURCHARGE;
        }
        return OversizeStatus.OK;
    }

    /** Whether a box's own weight capacity exceeds a service's max weight (in lb). */
    public static boolean exceedsServiceWeight(PackagePreset p, ServiceLimits limits) {
        if (p == null || p.getMaxWeight() == null || limits == null || limits.maxWeightLb() == null) return false;
        BigDecimal boxMaxLb = "KG".equalsIgnoreCase(p.getWeightUnit())
                ? p.getMaxWeight().multiply(LB_PER_KG) : p.getMaxWeight();
        return boxMaxLb.compareTo(new BigDecimal(limits.maxWeightLb())) > 0;
    }
}
