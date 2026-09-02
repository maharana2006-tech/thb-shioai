package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Every filter the unified order list understands. Global filters (status,
 * tenantId, search, resolution) combine with per-column filters (customer,
 * city, orderNo, tracking) — all applied server-side, before pagination.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderListFilters {

    /** PENDING | GENERATED | ERROR */
    private String status;

    /** Exact client scope (also the TENANT security boundary). */
    private String tenantId;

    /** Global keyword: order #, city, customer code, or tracking number. */
    private String search;

    /** READY | NEEDS_DETAILS | BLOCKED | CLIENT_MISSING */
    private String resolution;

    // ===== per-column filters (contains match) =====

    /** Customer/client code column. */
    private String customer;

    /** Destination column (matches city or state). */
    private String city;

    /** Order # column. */
    private String orderNo;

    /** Tracking number column (archive). */
    private String tracking;

    /** Created-date range (inclusive, yyyy-MM-dd). */
    private String createdFrom;
    private String createdTo;
    /** Order source filter: MANUAL | BULK | API | WMS | ERP. Null/blank = all. */
    private String source;

    /** Shipping-channel filter: D2C | B2B. Null/blank = all. */
    private String channel;
}
