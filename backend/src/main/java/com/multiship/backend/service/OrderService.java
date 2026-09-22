package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.OrderListFilters;
import com.multiship.backend.dto.OrderResponseDTO;
import com.multiship.backend.dto.OrderWithLinesDTO;
import com.multiship.backend.dto.PageResponseDTO;
import com.multiship.backend.dto.PaginationRequestDTO;

import java.util.Map;

public interface OrderService {

    // ===== SINGLE-ORDER METHODS =====

    ApiResponse<OrderResponseDTO> getOrderWithTracking(Integer orderNo);

    /**
     * V76 — edit the internal per-order ops note. Passing null clears
     * the note. Returns the updated OrderResponseDTO (via the same
     * mapper as getOrderWithTracking).
     */
    ApiResponse<OrderResponseDTO> updateOrderNote(Integer orderNo, String note);

    ApiResponse<OrderWithLinesDTO> getOrderWithLines(Integer orderNo);

    ApiResponse<Map<String, Object>> getDashboardStats();

    // ===== UNIFIED LIST =====

    /**
     * The one list endpoint behind every order table in the app: server-side
     * pagination, sorting, and filtering by status, tenant, keyword, and
     * resolution scenario (READY / NEEDS_DETAILS / BLOCKED).
     */
    ApiResponse<PageResponseDTO<OrderResponseDTO>> listOrders(
            OrderListFilters filters,
            boolean includeResolution,
            PaginationRequestDTO paginationRequest
    );

    /**
     * Select-all-filtered (2026-09-13) — return only the order numbers
     * matching the same filter set that {@link #listOrders} uses.
     * Powers the FE's "Select all M matching this filter" flow without
     * shuttling full-row payloads. Never returns a paginated slice —
     * caller must apply their own hard cap on the FE side if needed.
     */
    ApiResponse<java.util.List<Integer>> listOrderNos(OrderListFilters filters);

    /**
     * Batch-filter dropdown (2026-09-14) — distinct label_batch.batch_id
     * values (with counts) that match the current filter surface, so the
     * FE can render a picker of "batches shown by the current filters"
     * instead of asking the operator to remember the number.
     */
    ApiResponse<java.util.List<java.util.Map<String, Object>>> listBatches(OrderListFilters filters);

    /**
     * Work-queue tab counts: ready, needsDetails, chooseAccount, clientMissing,
     * failed, generated. Always tenant-wide — see the javadoc on
     * {@code OrderController#getQueueStats} for why no filter params
     * (batch, client, dates, keyword) are accepted here.
     */
    ApiResponse<Map<String, Long>> getQueueStats();
}
