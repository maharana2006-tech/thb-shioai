package com.multiship.backend.service.output;

/**
 * Sprint 52 — kinds of shipment document we generate and can route to a
 * client-configured output destination. Held as an enum instead of a free
 * string so a new type gets caught at compile time by every switch that
 * cares.
 *
 * <p>Persisted as a string in {@code client_output_destination.doc_type}
 * and {@code shipment_document.doc_type}; the SQL CHECK constraint keeps
 * the DB honest even if a caller bypasses JPA.
 */
public enum DocType {
    /** Shipping label — carrier-native ZPL raw or PDF depending on printer. */
    LABEL,
    /** Commercial invoice — PDF, present only on international shipments. */
    COMMERCIAL_INVOICE
}
