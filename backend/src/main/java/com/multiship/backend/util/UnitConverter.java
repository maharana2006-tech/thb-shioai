package com.multiship.backend.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Carrier-safe unit conversions for weight and dimensions.
 *
 * <p>The problem this exists to solve: operators in EU/UK/AU enter parcels in
 * KG and CM; UPS / FedEx / USPS default to LB and IN. Sending {@code 1.5} to
 * the carrier without a unit hint always meant 1.5 LB, so a 1.5 KG package
 * shipped as 0.68 KG — triggering carrier re-weight surcharges of $10-25 per
 * parcel at rating time and silent misdeclaration on customs paperwork.
 *
 * <p>Every conversion here is deterministic (constants from NIST), returns a
 * BigDecimal to avoid float drift, and rounds HALF_UP to 2 decimal places for
 * weights, 3 for dimensions — enough precision that the carrier reweigh
 * tolerance (usually ±0.1 LB / ±0.05 KG) is never crossed by rounding alone.
 *
 * <p>The connector-facing entry points are the four {@code to*} methods —
 * everything else is a helper. Unit strings are case-insensitive, trimmed,
 * and null-tolerant (null / blank / unknown = "assume already in the target
 * unit and return the value unchanged"). That way a legacy caller that hasn't
 * been updated to carry the unit alongside the number still works — it just
 * doesn't get a conversion.
 */
public final class UnitConverter {

    /** NIST: 1 kg = 2.20462262 lb. */
    private static final BigDecimal KG_TO_LB = new BigDecimal("2.20462262");
    /** NIST: 1 lb = 0.45359237 kg (exact). */
    private static final BigDecimal LB_TO_KG = new BigDecimal("0.45359237");
    /** NIST: 1 cm = 0.393700787 in. */
    private static final BigDecimal CM_TO_IN = new BigDecimal("0.393700787");
    /** NIST: 1 in = 2.54 cm (exact). */
    private static final BigDecimal IN_TO_CM = new BigDecimal("2.54");
    /** NIST: 1 kg = 35.27396195 oz. Used by SWSIM (USPS) which speaks ounces. */
    private static final BigDecimal KG_TO_OZ = new BigDecimal("35.27396195");
    /** 1 lb = 16 oz (exact). */
    private static final BigDecimal LB_TO_OZ = new BigDecimal("16");

    private static final int WEIGHT_SCALE = 2;
    private static final int DIMENSION_SCALE = 3;

    private UnitConverter() {}

    /** Convert a weight to pounds. Null / blank / "LB" / "LBS" returns value unchanged. */
    public static BigDecimal toPounds(BigDecimal value, String fromUnit) {
        if (value == null) return null;
        String u = normalize(fromUnit);
        if (u == null || "LB".equals(u) || "LBS".equals(u)) {
            return round(value, WEIGHT_SCALE);
        }
        if ("KG".equals(u) || "KGS".equals(u)) {
            return round(value.multiply(KG_TO_LB), WEIGHT_SCALE);
        }
        if ("OZ".equals(u)) {
            return round(value.divide(LB_TO_OZ, WEIGHT_SCALE + 4, RoundingMode.HALF_UP), WEIGHT_SCALE);
        }
        throw new IllegalArgumentException("Unsupported weight unit: " + fromUnit);
    }

    /** Convert a weight to kilograms. Null / blank / "KG" / "KGS" returns value unchanged. */
    public static BigDecimal toKilograms(BigDecimal value, String fromUnit) {
        if (value == null) return null;
        String u = normalize(fromUnit);
        if (u == null || "KG".equals(u) || "KGS".equals(u)) {
            return round(value, WEIGHT_SCALE);
        }
        if ("LB".equals(u) || "LBS".equals(u)) {
            return round(value.multiply(LB_TO_KG), WEIGHT_SCALE);
        }
        if ("OZ".equals(u)) {
            BigDecimal lbs = value.divide(LB_TO_OZ, WEIGHT_SCALE + 4, RoundingMode.HALF_UP);
            return round(lbs.multiply(LB_TO_KG), WEIGHT_SCALE);
        }
        throw new IllegalArgumentException("Unsupported weight unit: " + fromUnit);
    }

    /**
     * Convert a weight to ounces. SWSIM's {@code WeightOz} is US-only and
     * always in ounces regardless of the caller's preferred unit.
     */
    public static BigDecimal toOunces(BigDecimal value, String fromUnit) {
        if (value == null) return null;
        String u = normalize(fromUnit);
        if ("OZ".equals(u)) return round(value, WEIGHT_SCALE);
        if (u == null || "LB".equals(u) || "LBS".equals(u)) {
            return round(value.multiply(LB_TO_OZ), WEIGHT_SCALE);
        }
        if ("KG".equals(u) || "KGS".equals(u)) {
            return round(value.multiply(KG_TO_OZ), WEIGHT_SCALE);
        }
        throw new IllegalArgumentException("Unsupported weight unit: " + fromUnit);
    }

    /** Convert a dimension to inches. Null / blank / "IN" returns value unchanged. */
    public static BigDecimal toInches(BigDecimal value, String fromUnit) {
        if (value == null) return null;
        String u = normalize(fromUnit);
        if (u == null || "IN".equals(u)) return round(value, DIMENSION_SCALE);
        if ("CM".equals(u)) return round(value.multiply(CM_TO_IN), DIMENSION_SCALE);
        if ("MM".equals(u)) return round(value.multiply(CM_TO_IN).divide(BigDecimal.TEN, DIMENSION_SCALE + 4, RoundingMode.HALF_UP), DIMENSION_SCALE);
        throw new IllegalArgumentException("Unsupported dimension unit: " + fromUnit);
    }

    /** Convert a dimension to centimeters. Null / blank / "CM" returns value unchanged. */
    public static BigDecimal toCentimeters(BigDecimal value, String fromUnit) {
        if (value == null) return null;
        String u = normalize(fromUnit);
        if (u == null || "CM".equals(u)) return round(value, DIMENSION_SCALE);
        if ("IN".equals(u)) return round(value.multiply(IN_TO_CM), DIMENSION_SCALE);
        if ("MM".equals(u)) return round(value.divide(BigDecimal.TEN, DIMENSION_SCALE + 4, RoundingMode.HALF_UP), DIMENSION_SCALE);
        throw new IllegalArgumentException("Unsupported dimension unit: " + fromUnit);
    }

    /**
     * Which units a given carrier natively accepts on the wire. UPS/FedEx
     * accept both LB and KG (with a unit hint on the payload). USPS / Stamps
     * SWSIM only speaks ounces + inches.
     */
    public static String preferredWeightUnit(String carrierCode) {
        return "USPS".equalsIgnoreCase(carrierCode) ? "OZ" : "LB";
    }

    public static String preferredDimensionUnit(String carrierCode) {
        return "USPS".equalsIgnoreCase(carrierCode) ? "IN" : "IN";
    }

    private static String normalize(String unit) {
        if (unit == null) return null;
        String u = unit.trim().toUpperCase();
        return u.isEmpty() ? null : u;
    }

    private static BigDecimal round(BigDecimal v, int scale) {
        return v.setScale(scale, RoundingMode.HALF_UP);
    }
}
