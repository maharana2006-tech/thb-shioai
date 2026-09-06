package com.multiship.backend.dto;

/**
 * Commodity auto-split strategy — chosen by the operator when a
 * shipment's commodity count exceeds the carrier's cap (UPS=50,
 * DHL=25, etc). See docs/plans/commodity_autosplit.md for
 * background.
 *
 * <p>Presented to the operator in a modal after the initial submit
 * returns {@code errorCode=SPLIT_REQUIRED}. Operator picks; FE
 * re-submits with the chosen strategy.
 */
public enum SplitStrategy {

    /**
     * Duplicate physical packages, split invoice only.
     *
     * <p>Each sub-shipment carries THE SAME set of physical packages,
     * but each has a DIFFERENT slice of commodities. E.g. 3 packages
     * × 4 sub-shipments = 12 tracking numbers for 3 physical boxes.
     * Wasteful (carrier bills 4× shipping for 3 boxes) but
     * customs-simple — each label lists the full physical dims/weights.
     */
    SAME_PACKAGES,

    /**
     * Distribute physical packages proportionally.
     *
     * <p>Split packages across sub-shipments proportional to commodity
     * count. 190 commodities across 4 splits = ~48 per split;
     * 3 packages assigned round-robin to 3 splits. Last split may
     * have fewer packages. Customs paperwork per split names only the
     * commodities in that split's boxes.
     */
    PROPORTIONAL_PACKAGES,

    /**
     * One physical package per sub-shipment.
     *
     * <p>For an N-package order with M > cap commodities: N splits
     * each with 1 package and its own commodity slice — (M/N)
     * commodities per split. Uses {@code commodity.boxSeq} metadata
     * when present; falls back to proportional when absent.
     */
    ONE_PACKAGE_PER_SPLIT;
}
