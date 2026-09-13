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

    /** Work-queue tab counts: ready, needsDetails, blocked, failed, generated. */
    ApiResponse<Map<String, Long>> getQueueStats();
}
