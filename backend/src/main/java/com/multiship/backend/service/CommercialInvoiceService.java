package com.multiship.backend.service;

/**
 * Sprint 51 — renders the platform's own commercial-invoice PDF for an
 * international order.
 *
 * <p>An always-available operator document: an operator can print the
 * commercial invoice for any international order on demand. It reads the
 * customs data that the label pipeline persists ({@code order_customs} +
 * line items) and the client's ship-from / importer profile.
 */
public interface CommercialInvoiceService {

    /**
     * Render the commercial-invoice PDF for {@code orderNo}.
     *
     * @param orderNo the order to render
     * @return raw PDF bytes, ready to stream as {@code application/pdf}
     * @throws IllegalArgumentException when the order does not exist
     * @throws IllegalStateException    when the order has no customs data
     *                                  (domestic / not an international shipment)
     */
    byte[] render(Integer orderNo);
}
