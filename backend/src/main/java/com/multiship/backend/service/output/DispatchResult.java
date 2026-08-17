package com.multiship.backend.service.output;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Sprint 52 — outcome of routing one document to all configured
 * destinations for a (client, doc-type). Aggregated so a bulk-label
 * worker gets one object per dispatch instead of a stream of futures.
 *
 * <p>Callers can branch:
 * <ul>
 *   <li>All success → business as usual.</li>
 *   <li>Some failed → surface a warning; the DB copy is still saved so
 *       the label can be re-delivered manually.</li>
 *   <li>All failed → still not fatal — the shipment exists, the label
 *       exists in the DB, ops can re-drive from the admin page.</li>
 * </ul>
 */
@Data
@Builder
@AllArgsConstructor
public class DispatchResult {
    /** DB-copy shipment_document row id (never null when the payload was non-empty). */
    private Long shipmentDocumentId;

    /** How many destinations were configured for this (client, doc-type). */
    private int totalDestinations;

    /** How many delivered without error. */
    private int successCount;

    /** How many raised an OutputDeliveryException. */
    private int failureCount;

    /** Per-destination outcome — same order as the DB query (by id ASC). */
    private List<Item> items;

    @Data
    @Builder
    @AllArgsConstructor
    public static class Item {
        private Long destinationId;
        private DestinationType destinationType;
        private boolean success;
        /** Null on success; failure message on error (never carries the payload). */
        private String failureMessage;
    }

    public static DispatchResult empty(Long shipmentDocumentId) {
        return DispatchResult.builder()
                .shipmentDocumentId(shipmentDocumentId)
                .totalDestinations(0)
                .successCount(0)
                .failureCount(0)
                .items(new ArrayList<>())
                .build();
    }
}
