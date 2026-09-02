package com.multiship.backend.controller;

import com.multiship.backend.dto.LabelPackageDTO;
import com.multiship.backend.dto.OrderWithLinesDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks in the loop-bound contract that {@code /orders/{n}/label/*} uses
 * for multi-package rendering. The bug this closes (issue #545): pre-fix
 * the composite loop used {@code Order.packageCount} alone, so when the
 * write path left {@code packageCount=1} but {@code label_package} had
 * 2 rows (regenerate stomp path — see {@code CarrierServiceImpl} error-
 * path pre-V33), the FE picker showed "1 of 2" but the BE composite only
 * looped once. This helper matches the FE's own signal at
 * {@code LabelDocumentPage.tsx:449-451} so picker + composite agree.
 */
class OrderControllerEffectivePkgCountTest {

    @Test
    void null_data_defaults_to_1() {
        assertEquals(1, OrderController.effectivePkgCount(null));
    }

    @Test
    void empty_data_defaults_to_1() {
        OrderWithLinesDTO data = OrderWithLinesDTO.builder().build();
        assertEquals(1, OrderController.effectivePkgCount(data));
    }

    @Test
    void package_count_only_is_used_when_packages_absent() {
        // Auto-path first-time label: packageCount set at creation, label_package
        // not yet written → should honor packageCount.
        OrderWithLinesDTO data = OrderWithLinesDTO.builder().packageCount(3).build();
        assertEquals(3, OrderController.effectivePkgCount(data));
    }

    @Test
    void packages_length_wins_when_larger_than_packageCount() {
        // The 900016 bug pattern: label_package has 2 rows but packageCount stayed
        // at 1 (write-path stomp). Pre-fix the composite loop only rendered pkg 1.
        OrderWithLinesDTO data = OrderWithLinesDTO.builder()
                .packageCount(1)
                .packages(List.of(
                        LabelPackageDTO.builder().sequenceNumber(1).build(),
                        LabelPackageDTO.builder().sequenceNumber(2).build()))
                .build();
        assertEquals(2, OrderController.effectivePkgCount(data));
    }

    @Test
    void packageCount_wins_when_larger_than_packages_length() {
        // Auto-path retry mid-flight: intent = 3 pkgs (packageCount) but only
        // 2 label_package rows persisted so far. The higher intent wins so the
        // composite/picker doesn't drop the missing panel silently.
        OrderWithLinesDTO data = OrderWithLinesDTO.builder()
                .packageCount(3)
                .packages(List.of(
                        LabelPackageDTO.builder().sequenceNumber(1).build(),
                        LabelPackageDTO.builder().sequenceNumber(2).build()))
                .build();
        assertEquals(3, OrderController.effectivePkgCount(data));
    }

    @Test
    void agrees_with_FE_signal_when_both_match() {
        OrderWithLinesDTO data = OrderWithLinesDTO.builder()
                .packageCount(2)
                .packages(List.of(
                        LabelPackageDTO.builder().sequenceNumber(1).build(),
                        LabelPackageDTO.builder().sequenceNumber(2).build()))
                .build();
        assertEquals(2, OrderController.effectivePkgCount(data));
    }

    @Test
    void null_packageCount_with_populated_packages_uses_packages_length() {
        OrderWithLinesDTO data = OrderWithLinesDTO.builder()
                .packageCount(null)
                .packages(List.of(
                        LabelPackageDTO.builder().sequenceNumber(1).build(),
                        LabelPackageDTO.builder().sequenceNumber(2).build(),
                        LabelPackageDTO.builder().sequenceNumber(3).build()))
                .build();
        assertEquals(3, OrderController.effectivePkgCount(data));
    }
}
