package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * PR #548 — projection of {@link com.multiship.backend.model.ShipmentBatch}
 * exposed alongside {@link LabelPackageDTO}[] on the order response. Lets
 * the FE distinguish "master tracking" (per batch) from "child tracking"
 * (per piece).
 *
 * <p>A single-request MPS shipment produces exactly one batch row —
 * {@code masterTrackingNumber} is the carrier's master (FedEx piece 1's
 * tracking; UPS ShipmentIdentificationNumber; DHL shipmentTrackingNumber).
 *
 * <p>A shipment split across N carrier calls (over-cap; Sprint 48 B2)
 * produces N batch rows, each with its own master. Each
 * {@code LabelPackageDTO} carries the batch it belongs to via
 * {@code label_package.batch_id} (not exposed on the DTO today; joined
 * server-side).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentBatchDTO {
    /** 1-based within the order. batch 1..N corresponds to sub-request 1..N. */
    private Integer batchSeq;
    private String carrierCode;
    /**
     * The carrier's master tracking for this batch. This is the number
     * ops quote to customers ("your shipment: XYZ") — child trackings
     * on {@link LabelPackageDTO} are the per-piece events under it.
     */
    private String masterTrackingNumber;
    private String masterTrackingUrl;
    private String masterLabelUrl;
    private Integer packageCountInBatch;
    private BigDecimal shippingCost;
}
