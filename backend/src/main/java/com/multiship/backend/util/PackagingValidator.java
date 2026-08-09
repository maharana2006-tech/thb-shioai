package com.multiship.backend.util;

import com.multiship.backend.model.PackagePreset;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Data-driven pre-flight for shipment packaging: given a
 * {@link PackagePreset} row and the requested weight / dimensions,
 * checks the shipment against the preset's own thresholds.
 *
 * <p>Every rule reads its threshold from a column on {@code package_preset}
 * — no hardcoded limits. Add / edit rules by editing the row.
 *
 * <table>
 *   <caption>Rules evaluated</caption>
 *   <tr><th>Rule</th><th>Preset column</th><th>Formula</th></tr>
 *   <tr><td>MAX_WEIGHT</td><td>max_weight + weight_unit</td>
 *       <td>request weight &gt; max_weight</td></tr>
 *   <tr><td>MAX_LENGTH</td><td>length + dim_unit</td>
 *       <td>request length &gt; preset length</td></tr>
 *   <tr><td>MAX_WIDTH</td><td>width + dim_unit</td>
 *       <td>request width &gt; preset width</td></tr>
 *   <tr><td>MAX_HEIGHT</td><td>height + dim_unit</td>
 *       <td>request height &gt; preset height</td></tr>
 *   <tr><td>DIM_WEIGHT</td><td>dim_divisor + max_weight</td>
 *       <td>(L × W × H) / dim_divisor &gt; max_weight</td></tr>
 *   <tr><td>GIRTH</td><td>max_girth + dim_unit</td>
 *       <td>L + 2·(W + H) &gt; max_girth</td></tr>
 * </table>
 *
 * <p>Enforcement (HARD | SOFT) is a single {@code enforcement} column
 * on the preset. HARD violations are meant to block the shipment
 * (caller returns 422); SOFT violations are informational (caller
 * surfaces them but continues).
 */
public final class PackagingValidator {

    public enum RuleType { MAX_WEIGHT, MAX_LENGTH, MAX_WIDTH, MAX_HEIGHT, DIM_WEIGHT, GIRTH,
        /** Sprint 48 — parcel-service eligibility. Fires when a piece exceeds
         *  the carrier's parcel service caps (150 lb / 108 in / 165 in L+G
         *  for UPS/FedEx Ground; 70 lb for USPS) — the shipment can't ship
         *  parcel and requires LTL freight service instead. Also fires on
         *  shipment-total weight above the carrier's per-shipment cap. */
        PARCEL_LIMIT }
    public enum Severity { HARD, SOFT }

    /**
     * @param rule    which check failed
     * @param severity HARD or SOFT
     * @param message  operator-facing description already formatted with
     *                 the actual + limit values
     */
    public record Violation(RuleType rule, Severity severity, String message) {}

    /** Aggregate outcome — {@link #hardBlock()} is the boolean the caller usually branches on. */
    public record Outcome(List<Violation> violations) {
        public boolean hardBlock() {
            return violations.stream().anyMatch(v -> v.severity() == Severity.HARD);
        }
        /** Combine two outcomes — used when parcel-limit checks run alongside
         *  the standard packaging validation. Violations from both surface in
         *  a single {@link #combinedMessage()} so the operator sees every issue. */
        public Outcome merge(Outcome other) {
            if (other == null || other.violations.isEmpty()) return this;
            if (this.violations.isEmpty()) return other;
            java.util.List<Violation> all = new java.util.ArrayList<>(this.violations);
            all.addAll(other.violations);
            return new Outcome(all);
        }
        public String combinedMessage() {
            // Truncate to the first 5 violations so a 100-box shipment
            // with 100 failures doesn't produce an 8KB error string.
            List<Violation> shown = violations.size() > 5 ? violations.subList(0, 5) : violations;
            String joined = String.join(" ", shown.stream().map(Violation::message).toList());
            int remaining = violations.size() - shown.size();
            return remaining > 0 ? joined + " (…and " + remaining + " more violations)" : joined;
        }
    }

    private PackagingValidator() {}

    /**
     * Sprint 48 B8 — validate the shipment-level fields AND every per-box
     * entry in {@code packages}. Each per-box violation is tagged with
     * {@code "Box N: ..."} so operators can identify the offending box in
     * a multi-package shipment.
     *
     * <p>{@code preset} is the shipment-level preset (auto-picked or
     * operator-chosen); it's used as the reference for every box. Per-box
     * {@link com.multiship.backend.dto.PackageDetailDTO#getPackageType()}
     * overrides are NOT looked up here — that would require an N-way
     * repository fetch on the hot path. Ops should use per-preset SOFT
     * enforcement or split into separate shipments for exotic per-box
     * packaging.
     */
    public static Outcome validateAll(PackagePreset preset,
                                      BigDecimal shipmentWeight, String shipmentWeightUnit,
                                      BigDecimal shipmentLen, BigDecimal shipmentWid, BigDecimal shipmentHt,
                                      String shipmentDimUnit,
                                      List<com.multiship.backend.dto.PackageDetailDTO> packages) {
        List<Violation> combined = new ArrayList<>();
        // Shipment-level pass (existing behaviour).
        combined.addAll(validate(preset, shipmentWeight, shipmentWeightUnit,
                shipmentLen, shipmentWid, shipmentHt, shipmentDimUnit).violations());
        // Per-box pass — one validate() call per package with box-index
        // prefixed onto every violation message.
        if (packages != null) {
            for (int i = 0; i < packages.size(); i++) {
                com.multiship.backend.dto.PackageDetailDTO p = packages.get(i);
                if (p == null) continue;
                Outcome perBox = validate(preset,
                        p.getWeight(), p.getWeightUnit(),
                        p.getLength(), p.getWidth(), p.getHeight(), p.getDimUnit());
                int boxNo = p.getSequenceNumber() != null ? p.getSequenceNumber() : (i + 1);
                for (Violation v : perBox.violations()) {
                    combined.add(new Violation(v.rule(), v.severity(),
                            "Box " + boxNo + ": " + v.message()));
                }
            }
        }
        return new Outcome(combined);
    }

    /**
     * Run every applicable rule. A rule is skipped when its
     * threshold column on the preset is null — that column being null
     * means "no limit for this preset."
     *
     * @param preset      resolved package preset row (rules come from here)
     * @param weight      requested shipment weight (may be null)
     * @param weightUnit  unit for {@code weight}; falls back to preset.weightUnit
     * @param length      requested package length (may be null — falls back to preset.length)
     * @param width       requested package width (may be null — falls back to preset.width)
     * @param height      requested package height (may be null — falls back to preset.height)
     * @param dimUnit     unit for L/W/H; falls back to preset.dimUnit
     */
    public static Outcome validate(PackagePreset preset,
                                   BigDecimal weight, String weightUnit,
                                   BigDecimal length, BigDecimal width, BigDecimal height,
                                   String dimUnit) {
        List<Violation> out = new ArrayList<>();
        if (preset == null) return new Outcome(out);

        Severity severity = "SOFT".equalsIgnoreCase(preset.getEnforcement())
                ? Severity.SOFT : Severity.HARD;

        // Normalise weights to LB, dims to IN, for a single comparison basis.
        BigDecimal wLb = weight == null ? null : UnitConverter.toPounds(weight, weightUnit);
        BigDecimal maxWLb = preset.getMaxWeight() == null ? null
                : UnitConverter.toPounds(preset.getMaxWeight(), preset.getWeightUnit());

        String presetDimUnit = preset.getDimUnit();
        String effDimUnit = dimUnit != null ? dimUnit : presetDimUnit;
        BigDecimal lIn = length == null ? nullSafeToIn(preset.getLength(), presetDimUnit)
                : UnitConverter.toInches(length, effDimUnit);
        BigDecimal wIn = width == null ? nullSafeToIn(preset.getWidth(), presetDimUnit)
                : UnitConverter.toInches(width, effDimUnit);
        BigDecimal hIn = height == null ? nullSafeToIn(preset.getHeight(), presetDimUnit)
                : UnitConverter.toInches(height, effDimUnit);

        BigDecimal maxLIn = nullSafeToIn(preset.getLength(), presetDimUnit);
        BigDecimal maxWIn2 = nullSafeToIn(preset.getWidth(), presetDimUnit);
        BigDecimal maxHIn = nullSafeToIn(preset.getHeight(), presetDimUnit);
        BigDecimal maxGirthIn = nullSafeToIn(preset.getMaxGirth(), presetDimUnit);

        // MAX_WEIGHT
        if (wLb != null && maxWLb != null && wLb.compareTo(maxWLb) > 0) {
            out.add(new Violation(RuleType.MAX_WEIGHT, severity,
                    String.format("Weight %s lb exceeds %s limit of %s lb.",
                            plain(wLb), preset.getName(), plain(maxWLb))));
        }
        // Dimension-vs-preset checks only when the request supplied a
        // custom L/W/H — otherwise the request implicitly uses the
        // preset's own dimensions and the check is redundant.
        if (length != null && maxLIn != null && lIn != null && lIn.compareTo(maxLIn) > 0) {
            out.add(new Violation(RuleType.MAX_LENGTH, severity,
                    String.format("Length %s in exceeds %s limit of %s in.",
                            plain(lIn), preset.getName(), plain(maxLIn))));
        }
        if (width != null && maxWIn2 != null && wIn != null && wIn.compareTo(maxWIn2) > 0) {
            out.add(new Violation(RuleType.MAX_WIDTH, severity,
                    String.format("Width %s in exceeds %s limit of %s in.",
                            plain(wIn), preset.getName(), plain(maxWIn2))));
        }
        if (height != null && maxHIn != null && hIn != null && hIn.compareTo(maxHIn) > 0) {
            out.add(new Violation(RuleType.MAX_HEIGHT, severity,
                    String.format("Height %s in exceeds %s limit of %s in.",
                            plain(hIn), preset.getName(), plain(maxHIn))));
        }

        // DIM weight — skip when the preset has no divisor (flat-rate).
        if (preset.getDimDivisor() != null && preset.getDimDivisor() > 0
                && lIn != null && wIn != null && hIn != null && maxWLb != null) {
            BigDecimal vol = lIn.multiply(wIn).multiply(hIn);
            BigDecimal dimWeight = vol.divide(BigDecimal.valueOf(preset.getDimDivisor()), 2, RoundingMode.HALF_UP);
            if (dimWeight.compareTo(maxWLb) > 0) {
                out.add(new Violation(RuleType.DIM_WEIGHT, severity,
                        String.format("Dimensional weight %s lb (L·W·H / %d) exceeds %s limit of %s lb.",
                                plain(dimWeight), preset.getDimDivisor(), preset.getName(), plain(maxWLb))));
            }
        }

        // GIRTH = L + 2·(W + H)
        if (maxGirthIn != null && lIn != null && wIn != null && hIn != null) {
            BigDecimal girth = lIn.add(wIn.add(hIn).multiply(BigDecimal.valueOf(2)));
            if (girth.compareTo(maxGirthIn) > 0) {
                out.add(new Violation(RuleType.GIRTH, severity,
                        String.format("Girth L+2(W+H) = %s in exceeds %s limit of %s in.",
                                plain(girth), preset.getName(), plain(maxGirthIn))));
            }
        }

        return new Outcome(out);
    }

    private static BigDecimal nullSafeToIn(BigDecimal value, String unit) {
        return value == null ? null : UnitConverter.toInches(value, unit);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /**
     * Sprint 48 — parcel-service eligibility check. Fires HARD violations
     * (PARCEL_LIMIT) when any piece exceeds the carrier's parcel service
     * caps or the shipment total exceeds the carrier per-shipment cap.
     * The shipment then cannot ship as parcel and requires LTL freight
     * service — the message names the exact rule + threshold + carrier so
     * ops can either reduce/split or route to freight externally.
     *
     * <p>Limits per (carrier, service) come from
     * {@link PackageMath#defaultLimits(String, String)} — hardcoded per
     * carrier/service since these are stable carrier tariffs, not per-preset
     * config. Shipment-total limit is passed in (typically from
     * {@code carrier_shipping_limit.max_total_weight_lb}).
     *
     * @param carrier         canonical carrier code (UPS/FEDEX/USPS/DHL)
     * @param serviceCode     specific service (FEDEX_GROUND, UPS_NEXT_DAY_AIR, ...)
     * @param packages        per-box list — may be null / empty (single-box path)
     * @param shipmentWeight  top-level shipment weight for the single-box path
     * @param weightUnit      unit for {@code shipmentWeight} + per-box fallback
     * @param length/width/height/dimUnit  top-level dims for single-box path
     * @param maxTotalWeightLb  carrier per-shipment weight cap in LB; null skips the check
     */
    public static Outcome validateParcelLimits(String carrier, String serviceCode,
                                               List<com.multiship.backend.dto.PackageDetailDTO> packages,
                                               BigDecimal shipmentWeight, String weightUnit,
                                               BigDecimal length, BigDecimal width, BigDecimal height,
                                               String dimUnit,
                                               BigDecimal maxTotalWeightLb) {
        List<Violation> out = new ArrayList<>();
        PackageMath.ServiceLimits limits = PackageMath.defaultLimits(carrier, serviceCode);
        if (limits == null) return new Outcome(out);

        // Which pieces to check: prefer per-box list; else synthesize one
        // from top-level fields (single-box path).
        java.util.List<PieceView> pieces = new ArrayList<>();
        if (packages != null && !packages.isEmpty()) {
            for (int i = 0; i < packages.size(); i++) {
                com.multiship.backend.dto.PackageDetailDTO p = packages.get(i);
                if (p == null) continue;
                int seq = p.getSequenceNumber() != null ? p.getSequenceNumber() : (i + 1);
                pieces.add(new PieceView(seq,
                        p.getWeight(), p.getWeightUnit() != null ? p.getWeightUnit() : weightUnit,
                        p.getLength(), p.getWidth(), p.getHeight(),
                        p.getDimUnit() != null ? p.getDimUnit() : dimUnit));
            }
        } else {
            pieces.add(new PieceView(1, shipmentWeight, weightUnit, length, width, height, dimUnit));
        }

        BigDecimal totalWeightLb = BigDecimal.ZERO;
        for (PieceView piece : pieces) {
            BigDecimal wLb = piece.weight() == null ? null : UnitConverter.toPounds(piece.weight(), piece.weightUnit());
            if (wLb != null) totalWeightLb = totalWeightLb.add(wLb);
            checkPiece(out, limits, piece, wLb, carrier, serviceCode);
        }

        // Shipment-total cap — sum of all piece weights vs carrier per-shipment max.
        if (maxTotalWeightLb != null && maxTotalWeightLb.signum() > 0
                && totalWeightLb.compareTo(maxTotalWeightLb) > 0) {
            out.add(new Violation(RuleType.PARCEL_LIMIT, Severity.HARD,
                    String.format(
                            "Total shipment weight %s lb exceeds %s per-shipment parcel limit of %s lb. "
                                    + "This shipment cannot ship as one parcel request — split across "
                                    + "multiple shipments or use LTL freight. Contact your carrier for a freight quote.",
                            plain(totalWeightLb), safeCarrier(carrier), plain(maxTotalWeightLb))));
        }
        return new Outcome(out);
    }

    private static void checkPiece(List<Violation> out, PackageMath.ServiceLimits limits,
                                   PieceView piece, BigDecimal weightLb,
                                   String carrier, String serviceCode) {
        String svc = describeService(carrier, serviceCode);
        // 1. Weight
        if (weightLb != null && limits.maxWeightLb() != null
                && weightLb.compareTo(new BigDecimal(limits.maxWeightLb())) > 0) {
            out.add(new Violation(RuleType.PARCEL_LIMIT, Severity.HARD,
                    String.format("Box %d: %s lb exceeds %s parcel weight limit of %d lb. "
                                    + "Requires LTL freight service — contact your carrier for a freight quote.",
                            piece.seq(), plain(weightLb), svc, limits.maxWeightLb())));
        }
        // 2. Length
        BigDecimal lIn = nullSafeToIn(piece.length(), piece.dimUnit());
        if (lIn != null && limits.maxLengthIn() != null
                && lIn.compareTo(new BigDecimal(limits.maxLengthIn())) > 0) {
            out.add(new Violation(RuleType.PARCEL_LIMIT, Severity.HARD,
                    String.format("Box %d: length %s in exceeds %s parcel length limit of %d in. "
                                    + "Requires LTL freight service — contact your carrier.",
                            piece.seq(), plain(lIn), svc, limits.maxLengthIn())));
        }
        // 3. Length + girth
        BigDecimal wIn = nullSafeToIn(piece.width(), piece.dimUnit());
        BigDecimal hIn = nullSafeToIn(piece.height(), piece.dimUnit());
        if (lIn != null && wIn != null && hIn != null && limits.maxLengthGirthIn() != null) {
            BigDecimal lpg = lIn.add(wIn.add(hIn).multiply(BigDecimal.valueOf(2)));
            if (lpg.compareTo(new BigDecimal(limits.maxLengthGirthIn())) > 0) {
                out.add(new Violation(RuleType.PARCEL_LIMIT, Severity.HARD,
                        String.format("Box %d: length+girth %s in exceeds %s parcel L+G limit of %d in. "
                                        + "Requires LTL freight service — contact your carrier.",
                                piece.seq(), plain(lpg), svc, limits.maxLengthGirthIn())));
            }
        }
    }

    private static String describeService(String carrier, String serviceCode) {
        StringBuilder sb = new StringBuilder(carrier == null ? "carrier" : carrier);
        if (serviceCode != null && !serviceCode.isBlank()) {
            sb.append(" ").append(serviceCode);
        }
        return sb.toString();
    }

    private static String safeCarrier(String carrier) {
        return carrier == null || carrier.isBlank() ? "the carrier's" : carrier;
    }

    /** Internal view of one piece for parcel-eligibility checks. */
    private record PieceView(int seq, BigDecimal weight, String weightUnit,
                             BigDecimal length, BigDecimal width, BigDecimal height, String dimUnit) {}
}
