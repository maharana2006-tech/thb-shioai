package com.multiship.backend.dto.wms;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Summary of a WMS shippable-orders pull. */
@Data
@Builder
public class WmsPullResultDTO {
    /** Whether a WMS base URL + key are configured. False → nothing was pulled. */
    private boolean configured;
    /** How many shippable orders the WMS returned. */
    private int fetched;
    /** How many were newly imported as PENDING orders (source = WMS). */
    private int imported;
    /** How many were skipped as already-imported (idempotent re-pull). */
    private int skipped;
    /** How many failed to import (bad/missing data). */
    private int failed;
    /** Batch id all orders from this fetch were grouped under (null if none imported). */
    private Integer batchId;
    /** Import-history batch id recording this fetch (null if none imported / not recorded). */
    private Long importBatchId;
    /** The order numbers created by this pull. */
    private List<Integer> importedOrderNos;
    /** Per-row notes (skips / failures) for the operator. */
    private List<String> messages;
}
