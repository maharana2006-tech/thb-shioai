package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.CarrierApiLogEntity;
import com.multiship.backend.repository.CarrierApiLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/** V114 — admin surface for the per-request carrier API log. */
@Tag(name = "Carrier API log",
        description = "Durable per-request carrier API round-trips. Paginated; row-level body view.")
@RestController
@RequestMapping("/api/v1/admin/carrier-api-log")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminCarrierApiLogController {

    private static final int MAX_PAGE_SIZE = 200;

    private final CarrierApiLogRepository repo;

    @Operation(summary = "Paginated list, newest first. Filters optional. Body preview truncated.")
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(required = false) String carrier,
            @RequestParam(required = false) Long orderNo,
            @RequestParam(required = false) String tracking,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int clampedSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int clampedPage = Math.max(0, page);
        Page<CarrierApiLogEntity> result = repo.search(
                blankToNull(carrier), orderNo, blankToNull(tracking),
                PageRequest.of(clampedPage, clampedSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        Map<String, Object> body = new HashMap<>();
        body.put("items", result.getContent().stream().map(AdminCarrierApiLogController::toSummary).toList());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return ok(body);
    }

    @Operation(summary = "Full row including request + response bodies.")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFull(@PathVariable Long id) {
        CarrierApiLogEntity row = repo.findById(id).orElse(null);
        if (row == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.<Map<String, Object>>builder()
                            .status("ERROR").code(404).errorCode("API_LOG_NOT_FOUND")
                            .message("Row " + id + " not found")
                            .timestamp(LocalDateTime.now()).build());
        }
        return ok(toFull(row));
    }

    private static Map<String, Object> toSummary(CarrierApiLogEntity r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getId());
        m.put("requestId", r.getRequestId());
        m.put("carrier", r.getCarrier());
        m.put("method", r.getMethod());
        m.put("url", r.getUrl());
        m.put("statusCode", r.getStatusCode());
        m.put("latencyMs", r.getLatencyMs());
        m.put("errorMessage", r.getErrorMessage());
        m.put("orderNo", r.getOrderNo());
        m.put("tracking", r.getTracking());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private static Map<String, Object> toFull(CarrierApiLogEntity r) {
        Map<String, Object> m = toSummary(r);
        m.put("requestBody", r.getRequestBody());
        m.put("responseBody", r.getResponseBody());
        return m;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("SUCCESS").code(200).data(body)
                .timestamp(LocalDateTime.now()).build());
    }
}
