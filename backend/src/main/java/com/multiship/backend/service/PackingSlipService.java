package com.multiship.backend.service;

/**
 * Sprint 42 — renders a tenant-branded packing slip PDF for an order.
 * The output is meant to be printed on regular paper and slipped
 * inside the parcel next to the carrier's shipping label.
 */
public interface PackingSlipService {

    /**
     * Render the packing slip for the given order. Applies the tenant's
     * template if one is configured (via {@link LabelTemplateService});
     * otherwise falls back to the platform default; otherwise to
     * hard-coded defaults so we never fail to produce a slip.
     *
     * @param orderNo the order to render
     * @return raw PDF bytes, ready to stream as {@code application/pdf}
     * @throws IllegalArgumentException when the order does not exist
     */
    byte[] render(Integer orderNo);
}
