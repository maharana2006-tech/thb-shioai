package com.multiship.backend.util;

import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.model.PackagePreset;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 48 B8 — validateAll: shipment-level pass + per-box pass with
 * box-index tagging, message truncation at 5 violations.
 */
class PackagingValidatorAllTest {

    private PackagePreset envelopePreset() {
        PackagePreset p = new PackagePreset();
        p.setName("FedEx Envelope");
        p.setMaxWeight(new BigDecimal("1"));  // 1 lb cap
        p.setWeightUnit("LB");
        p.setLength(new BigDecimal("12.5"));
        p.setWidth(new BigDecimal("9.5"));
        p.setHeight(new BigDecimal("0.5"));
        p.setDimUnit("IN");
        p.setEnforcement("HARD");
        return p;
    }

    @Test
    void singleShipmentLevelViolationSurfaces() {
        // No per-box packages; shipment-level weight over limit.
        PackagingValidator.Outcome out = PackagingValidator.validateAll(
                envelopePreset(),
                new BigDecimal("5"), "LB",  // 5 lb > 1 lb limit
                null, null, null, null,
                null);
        assertTrue(out.hardBlock());
        assertEquals(1, out.violations().size());
        assertTrue(out.combinedMessage().contains("Weight 5 lb exceeds"));
    }

    @Test
    void perBoxViolationsAreTaggedWithBoxIndex() {
        // 3 packages: box 1 = 0.5 lb (OK), box 2 = 20 lb (over), box 3 = 30 lb (over).
        List<PackageDetailDTO> pkgs = List.of(
                PackageDetailDTO.builder().sequenceNumber(1).weight(new BigDecimal("0.5")).weightUnit("LB").build(),
                PackageDetailDTO.builder().sequenceNumber(2).weight(new BigDecimal("20")).weightUnit("LB").build(),
                PackageDetailDTO.builder().sequenceNumber(3).weight(new BigDecimal("30")).weightUnit("LB").build());

        PackagingValidator.Outcome out = PackagingValidator.validateAll(
                envelopePreset(),
                null, null,  // no shipment-level fields → no shipment-level violation
                null, null, null, null,
                pkgs);

        assertTrue(out.hardBlock());
        assertEquals(2, out.violations().size(), "boxes 2 + 3 fail; box 1 passes");
        assertTrue(out.combinedMessage().contains("Box 2:"), "message tags box 2");
        assertTrue(out.combinedMessage().contains("Box 3:"), "message tags box 3");
        assertFalse(out.combinedMessage().contains("Box 1:"), "box 1 passes so is not tagged");
    }

    @Test
    void messageTruncatesAtFiveViolationsWithSuffix() {
        // 10 packages all over the weight limit.
        List<PackageDetailDTO> pkgs = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            pkgs.add(PackageDetailDTO.builder().sequenceNumber(i)
                    .weight(new BigDecimal("20")).weightUnit("LB").build());
        }
        PackagingValidator.Outcome out = PackagingValidator.validateAll(
                envelopePreset(),
                null, null, null, null, null, null,
                pkgs);
        assertEquals(10, out.violations().size(), "all 10 pkgs are violations");
        String msg = out.combinedMessage();
        assertTrue(msg.contains("Box 1:"));
        assertTrue(msg.contains("Box 5:"));
        assertFalse(msg.contains("Box 6:"), "truncated at 5");
        assertTrue(msg.contains("and 5 more"), "suffix names the remaining count");
    }

    @Test
    void emptyPackagesListYieldsOnlyShipmentLevelChecks() {
        PackagingValidator.Outcome out = PackagingValidator.validateAll(
                envelopePreset(),
                new BigDecimal("0.5"), "LB",  // OK
                null, null, null, null,
                List.of());
        assertFalse(out.hardBlock());
        assertTrue(out.violations().isEmpty());
    }

    @Test
    void nullPresetIsANoOp() {
        PackagingValidator.Outcome out = PackagingValidator.validateAll(
                null,
                new BigDecimal("999"), "LB",
                null, null, null, null,
                List.of(PackageDetailDTO.builder().weight(new BigDecimal("999")).weightUnit("LB").build()));
        assertFalse(out.hardBlock());
        assertTrue(out.violations().isEmpty());
    }

    /* -------------------- Sprint 48 freight/LTL: validateParcelLimits -------------------- */

    @Test
    void parcelLimit_pieceOverWeightTriggersFreightMessage() {
        // UPS Ground parcel cap = 150 lb. Box 1 = 160 lb should fire PARCEL_LIMIT
        // with a message that names the exact limit + points to LTL freight.
        PackagingValidator.Outcome out = PackagingValidator.validateParcelLimits(
                "UPS", "GROUND",
                List.of(PackageDetailDTO.builder().sequenceNumber(1)
                        .weight(new BigDecimal("160")).weightUnit("LB").build()),
                null, null, null, null, null, null,
                null);
        assertTrue(out.hardBlock());
        assertEquals(1, out.violations().size());
        String msg = out.violations().get(0).message();
        assertTrue(msg.contains("160"), "message names actual weight");
        assertTrue(msg.contains("150"), "message names the parcel cap");
        assertTrue(msg.contains("LTL freight"), "message points to LTL freight");
    }

    @Test
    void parcelLimit_pieceOverLengthTriggersFreightMessage() {
        // FedEx Ground max length = 108 in. A 120 in box → PARCEL_LIMIT.
        PackagingValidator.Outcome out = PackagingValidator.validateParcelLimits(
                "FEDEX", "FEDEX_GROUND",
                List.of(PackageDetailDTO.builder().sequenceNumber(1)
                        .weight(new BigDecimal("50")).weightUnit("LB")
                        .length(new BigDecimal("120")).width(new BigDecimal("10"))
                        .height(new BigDecimal("10")).dimUnit("IN").build()),
                null, null, null, null, null, null,
                null);
        assertTrue(out.hardBlock());
        // Two violations: length AND length+girth may both trigger.
        assertTrue(out.violations().stream().anyMatch(v ->
                v.message().contains("length") && v.message().contains("108")));
    }

    @Test
    void parcelLimit_totalShipmentWeightOverCapTriggers() {
        // 40 boxes × 800 lb each = 32,000 lb; UPS per-shipment cap = 30,000 lb.
        java.util.List<PackageDetailDTO> boxes = new java.util.ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            boxes.add(PackageDetailDTO.builder().sequenceNumber(i)
                    .weight(new BigDecimal("800")).weightUnit("LB").build());
        }
        PackagingValidator.Outcome out = PackagingValidator.validateParcelLimits(
                "UPS", "GROUND", boxes,
                null, null, null, null, null, null,
                new BigDecimal("30000"));
        // Per-piece weight (800 lb > 150 lb) fires 40 violations + 1 total.
        assertTrue(out.hardBlock());
        assertTrue(out.violations().stream().anyMatch(v ->
                v.message().contains("Total shipment weight") && v.message().contains("30000")));
    }

    @Test
    void parcelLimit_underAllCapsIsOk() {
        // 100 lb box × 24 in × 18 in × 12 in — well within UPS Ground caps.
        PackagingValidator.Outcome out = PackagingValidator.validateParcelLimits(
                "UPS", "GROUND",
                List.of(PackageDetailDTO.builder().sequenceNumber(1)
                        .weight(new BigDecimal("100")).weightUnit("LB")
                        .length(new BigDecimal("24")).width(new BigDecimal("18"))
                        .height(new BigDecimal("12")).dimUnit("IN").build()),
                null, null, null, null, null, null,
                new BigDecimal("30000"));
        assertFalse(out.hardBlock());
        assertTrue(out.violations().isEmpty());
    }

    @Test
    void mergeCombinesTwoOutcomes() {
        PackagingValidator.Outcome a = PackagingValidator.validateAll(
                envelopePreset(),
                new BigDecimal("5"), "LB",
                null, null, null, null,
                null);  // 1 violation: weight over envelope 1 lb cap
        PackagingValidator.Outcome b = PackagingValidator.validateParcelLimits(
                "UPS", "GROUND",
                List.of(PackageDetailDTO.builder().sequenceNumber(1)
                        .weight(new BigDecimal("160")).weightUnit("LB").build()),
                null, null, null, null, null, null,
                null);  // 1 violation: piece over parcel weight
        PackagingValidator.Outcome merged = a.merge(b);
        assertEquals(2, merged.violations().size());
        assertTrue(merged.hardBlock());
    }
}
