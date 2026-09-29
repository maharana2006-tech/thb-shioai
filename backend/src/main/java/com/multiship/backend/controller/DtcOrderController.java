package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.DtcOrder;
import com.multiship.backend.repository.DtcOrderRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D2C History — read the dtc_orders table filled by the Oracle sync
 * (POST /api/v1/dtc/sync/oracle). Postgres only, so it works even when
 * multiship.oracle.enabled is false.
 */
@Tag(name = "D2C Orders", description = "Synced DTC orders (dtc_orders table)")
@RestController
@RequestMapping("/api/v1/dtc/orders")
@RequiredArgsConstructor
public class DtcOrderController {

    /** Sortable fields — anything else falls back to createdAt. */
    private static final Set<String> SORTABLE = Set.of(
            "createdAt", "batchId", "orderNo", "toteNumber", "tenantId",
            "shipName", "shipToCity", "shipToState", "shipVia", "weight", "shipDate");

    private final DtcOrderRepository dtcOrderRepository;

    @Operation(summary = "List synced DTC orders",
            description = "Paged. Optional tenantId filter and q search over batch, tote, order #, PO, ship-to name/city.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "DESC") String dir,
            @RequestParam(defaultValue = "") String tenantId,
            @RequestParam(defaultValue = "") String q) {

        String sortField = SORTABLE.contains(sort) ? sort : "createdAt";
        Sort.Direction direction = "ASC".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                Sort.by(direction, sortField).and(Sort.by(Sort.Direction.DESC, "id")));

        Page<DtcOrder> result = dtcOrderRepository.search(tenantId.trim(), q.trim(), pageable);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", result.getContent());
        data.put("pageNumber", result.getNumber());
        data.put("pageSize", result.getSize());
        data.put("totalElements", result.getTotalElements());
        data.put("totalPages", result.getTotalPages());

        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message("DTC orders").data(data).build());
    }

    @Operation(summary = "Tenants present in dtc_orders", description = "For the D2C History tenant filter.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/tenants")
    public ResponseEntity<ApiResponse<List<String>>> tenants() {
        return ResponseEntity.ok(ApiResponse.<List<String>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message("DTC tenants").data(dtcOrderRepository.findDistinctTenantIds()).build());
    }
}
