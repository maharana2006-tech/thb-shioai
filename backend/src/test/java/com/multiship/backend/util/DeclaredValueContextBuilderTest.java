package com.multiship.backend.util;

import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.PackageDetailDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 48 B11 — items → per-package declared value derivation.
 */
class DeclaredValueContextBuilderTest {

    private CustomsCommodityDTO item(String desc, int qty, String unitValue, Integer boxSeq) {
        return CustomsCommodityDTO.builder()
                .description(desc)
                .quantity(qty)
                .unitValue(new BigDecimal(unitValue))
                .boxSeq(boxSeq)
                .build();
    }

    @Test
    void allItemsAssignedProducesPerBoxTotals() {
        // 3 boxes: box 1 = 2×$50 + 1×$30 = $130; box 2 = 1×$200; box 3 = 3×$10 = $30.
        List<CustomsCommodityDTO> items = List.of(
                item("A", 2, "50", 1),
                item("B", 1, "30", 1),
                item("C", 1, "200", 2),
                item("D", 3, "10", 3));

        DeclaredValueContextBuilder.DeclaredValueContext ctx =
                DeclaredValueContextBuilder.build(items, 3, "USD", null);

        assertEquals(3, ctx.perPackage().size());
        assertEquals(new BigDecimal("130"), ctx.perPackage().get(0));
        assertEquals(new BigDecimal("200"), ctx.perPackage().get(1));
        assertEquals(new BigDecimal("30"), ctx.perPackage().get(2));
        assertEquals(new BigDecimal("360"), ctx.shipmentTotal());
        assertEquals(new BigDecimal("360"), ctx.customsTotal(),
                "customsTotal == shipmentTotal when items are the source");
    }

    @Test
    void unassignedItemsFallIntoBoxOne() {
        // All items unassigned (boxSeq null) → all collapse into box 1
        // (single-box semantics, backward-compat with legacy CI).
        List<CustomsCommodityDTO> items = List.of(
                item("A", 1, "100", null),
                item("B", 2, "50", null));

        DeclaredValueContextBuilder.DeclaredValueContext ctx =
                DeclaredValueContextBuilder.build(items, 3, "USD", null);

        assertEquals(new BigDecimal("200"), ctx.perPackage().get(0));
        assertEquals(BigDecimal.ZERO, ctx.perPackage().get(1));
        assertEquals(BigDecimal.ZERO, ctx.perPackage().get(2));
        assertEquals(new BigDecimal("200"), ctx.shipmentTotal());
    }

    @Test
    void emptyItemsWithFallbackSplitsEvenlyAcrossBoxes() {
        // No items → distribute the shipment-level fallback evenly.
        // 3 boxes × $100 total → $33.33, $33.33, $33.34 (rounding on last).
        DeclaredValueContextBuilder.DeclaredValueContext ctx =
                DeclaredValueContextBuilder.build(List.of(), 3, "USD", new BigDecimal("100"));

        assertEquals(new BigDecimal("33.33"), ctx.perPackage().get(0));
        assertEquals(new BigDecimal("33.33"), ctx.perPackage().get(1));
        assertEquals(new BigDecimal("33.34"), ctx.perPackage().get(2),
                "last box absorbs rounding remainder so sum == 100 exactly");
        assertEquals(new BigDecimal("100.00"), ctx.shipmentTotal());
    }

    @Test
    void emptyItemsAndNoFallbackYieldsAllZeros() {
        DeclaredValueContextBuilder.DeclaredValueContext ctx =
                DeclaredValueContextBuilder.build(null, 2, "USD", null);
        assertEquals(BigDecimal.ZERO, ctx.perPackage().get(0));
        assertEquals(BigDecimal.ZERO, ctx.perPackage().get(1));
        assertEquals(BigDecimal.ZERO, ctx.shipmentTotal());
    }

    @Test
    void batchMode_itemsForOutOfBatchBoxesAreDropped() {
        // After auto-split: original shipment had 5 boxes with items
        // assigned to seq 1..5. This batch has only boxes 41 + 42 (renumbered
        // from original 4 + 5). Items with boxSeq 1..3 belong to OTHER batches
        // and must be skipped (else they'd land in the wrong box here).
        List<CustomsCommodityDTO> items = List.of(
                item("A", 1, "10", 1),   // other batch — DROP
                item("B", 1, "20", 2),   // other batch — DROP
                item("C", 1, "30", 4),   // this batch, box 4 → local idx 0
                item("D", 1, "40", 5));  // this batch, box 5 → local idx 1

        List<PackageDetailDTO> batchPackages = List.of(
                PackageDetailDTO.builder().sequenceNumber(4).weight(new BigDecimal("1")).build(),
                PackageDetailDTO.builder().sequenceNumber(5).weight(new BigDecimal("1")).build());

        DeclaredValueContextBuilder.DeclaredValueContext ctx =
                DeclaredValueContextBuilder.build(items, 2, batchPackages, "USD", null);

        assertEquals(new BigDecimal("30"), ctx.perPackage().get(0), "box 4 got item C");
        assertEquals(new BigDecimal("40"), ctx.perPackage().get(1), "box 5 got item D");
        assertEquals(new BigDecimal("70"), ctx.shipmentTotal(),
                "items A/B (other batches) not included");
    }

    @Test
    void mixedAssignment_unassignedItemsGoToBoxOne() {
        // Some items assigned, some not — unassigned fall to box 1.
        List<CustomsCommodityDTO> items = List.of(
                item("A", 1, "100", 1),   // box 1
                item("B", 1, "50", null), // unassigned → box 1
                item("C", 1, "200", 2));  // box 2

        DeclaredValueContextBuilder.DeclaredValueContext ctx =
                DeclaredValueContextBuilder.build(items, 2, "USD", null);

        assertEquals(new BigDecimal("150"), ctx.perPackage().get(0), "100 + 50 unassigned");
        assertEquals(new BigDecimal("200"), ctx.perPackage().get(1));
    }

    @Test
    void currencyPropagates() {
        DeclaredValueContextBuilder.DeclaredValueContext ctx =
                DeclaredValueContextBuilder.build(List.of(item("A", 1, "10", 1)), 1, "EUR", null);
        assertEquals("EUR", ctx.currency());
    }

    @Test
    void sumInvariant_perPackageSumsToShipmentTotal() {
        List<CustomsCommodityDTO> items = List.of(
                item("A", 3, "17.33", 1),
                item("B", 1, "42.99", 2),
                item("C", 5, "1.01", 2));
        DeclaredValueContextBuilder.DeclaredValueContext ctx =
                DeclaredValueContextBuilder.build(items, 2, "USD", null);
        BigDecimal sum = ctx.perPackage().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(sum.compareTo(ctx.shipmentTotal()) == 0,
                "sum(perPackage) must equal shipmentTotal exactly");
    }
}
