package com.multiship.backend.controller;

import com.multiship.backend.dto.LabelPackageDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PR #548 — locks in the badge-text contract used by
 * {@code OrderController.getLabelPreviewPng} to overlay a "PKG N of M"
 * annotation on each composite panel. Some carriers' MPS ZPL omits the
 * pkg counter (UPS in particular), so the operator relies on this badge
 * to identify which physical box each printed label goes on.
 */
class OrderControllerBadgeForTest {

    @Test
    void invalid_range_returns_null() {
        assertNull(OrderController.badgeFor(0, 3, List.of()));
        assertNull(OrderController.badgeFor(4, 3, List.of()));
        assertNull(OrderController.badgeFor(1, 0, List.of()));
    }

    @Test
    void no_packages_returns_pkg_of_total_only() {
        assertEquals("PKG 1 OF 3", OrderController.badgeFor(1, 3, null));
        assertEquals("PKG 2 OF 3", OrderController.badgeFor(2, 3, List.of()));
    }

    @Test
    void matches_by_sequence_number() {
        List<LabelPackageDTO> packages = List.of(
                LabelPackageDTO.builder().sequenceNumber(1).trackingNumber("1Z999AA10123456784").build(),
                LabelPackageDTO.builder().sequenceNumber(2).trackingNumber("1Z999AA10123456785").build(),
                LabelPackageDTO.builder().sequenceNumber(3).trackingNumber("1Z999AA10123456786").build());
        assertEquals("PKG 2 OF 3\n1Z999AA10123456785",
                OrderController.badgeFor(2, 3, packages));
    }

    @Test
    void falls_back_to_positional_when_sequenceNumber_null() {
        // Legacy rows before Sprint 47 sometimes have null sequenceNumber.
        // The badge should still find the pkg via positional index.
        List<LabelPackageDTO> packages = List.of(
                LabelPackageDTO.builder().trackingNumber("A").build(),
                LabelPackageDTO.builder().trackingNumber("B").build());
        assertEquals("PKG 2 OF 2\nB", OrderController.badgeFor(2, 2, packages));
    }

    @Test
    void skips_tracking_when_blank() {
        // Empty tracking on a per-piece row (rare — carrier response missing
        // a piece's number). Fall back to pkg count only.
        List<LabelPackageDTO> packages = List.of(
                LabelPackageDTO.builder().sequenceNumber(1).trackingNumber("").build());
        assertEquals("PKG 1 OF 1", OrderController.badgeFor(1, 1, packages));
    }

    @Test
    void handles_out_of_order_packages() {
        // label_package rows returned in reverse order — findByOrderNoOrderBy
        // usually guarantees ascending but the helper shouldn't rely on it.
        List<LabelPackageDTO> packages = List.of(
                LabelPackageDTO.builder().sequenceNumber(3).trackingNumber("C").build(),
                LabelPackageDTO.builder().sequenceNumber(1).trackingNumber("A").build(),
                LabelPackageDTO.builder().sequenceNumber(2).trackingNumber("B").build());
        assertEquals("PKG 2 OF 3\nB", OrderController.badgeFor(2, 3, packages));
    }
}
