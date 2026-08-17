package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierShippingLimitRequest;
import com.multiship.backend.dto.CarrierShippingLimitResponse;
import com.multiship.backend.service.CarrierLimitAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Sprint 52 — admin CRUD for the {@code carrier_shipping_limit} catalog.
 * Powers the {@code /settings/carrier-limits} page: list, create, edit and
 * delete rows without a code deploy. ADMIN-only; every mutation invalidates
 * the resolver cache in {@link com.multiship.backend.service.CarrierLimitService}
 * so the change reaches the shipment-create path immediately.
 *
 * <p>Modelled on {@link AdminUserController} — same response envelope
 * shape, same {@code @PreAuthorize} boundary, no per-tenant scoping
 * (carrier caps are platform-wide catalog).
 */
@Tag(name = "Admin carrier limits",
        description = "Sprint 52 — CRUD for carrier_shipping_limit rows (per-carrier / per-service MPS + weight + commodity caps).")
@RestController
@RequestMapping("/api/v1/admin/carrier-shipping-limits")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class CarrierLimitAdminController {

    private final CarrierLimitAdminService service;

    @Operation(summary = "List rows",
            description = "Paginated (default 50 per page, max 200). Sorted by carrier, service, scope.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<CarrierShippingLimitResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<CarrierShippingLimitResponse> data = service.list(page, size);
        return ResponseEntity.ok(ApiResponse.<List<CarrierShippingLimitResponse>>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message(data.size() + " limit row(s).")
                .data(data).build());
    }

    @Operation(summary = "Fetch one row")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CarrierShippingLimitResponse>> get(@PathVariable Long id) {
        Optional<CarrierShippingLimitResponse> found = service.get(id);
        return found
                .map(dto -> ResponseEntity.ok(ok("Row " + id + ".", dto)))
                .orElseGet(() -> notFound(id));
    }

    @Operation(summary = "Create a new row",
            description = "Server sets effectiveFrom = now. Response is the created row (including its id).")
    @PostMapping
    public ResponseEntity<ApiResponse<CarrierShippingLimitResponse>> create(
            @Valid @RequestBody CarrierShippingLimitRequest body) {
        CarrierShippingLimitResponse created = service.create(body);
        return ResponseEntity.status(201).body(ApiResponse.<CarrierShippingLimitResponse>builder()
                .status("SUCCESS").code(201).timestamp(LocalDateTime.now())
                .message("Row created.")
                .data(created).build());
    }

    @Operation(summary = "Update an existing row",
            description = "Full replace of every mutable field. effectiveFrom stays put — insert a new row to supersede a cap.")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CarrierShippingLimitResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody CarrierShippingLimitRequest body) {
        return service.update(id, body)
                .map(dto -> ResponseEntity.ok(ok("Row updated.", dto)))
                .orElseGet(() -> notFound(id));
    }

    @Operation(summary = "Delete a row",
            description = "Hard delete. To keep a row for history but disable it, PUT with active=false instead.")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        boolean deleted = service.delete(id);
        if (!deleted) {
            return ResponseEntity.status(404).body(ApiResponse.<Void>builder()
                    .status("ERROR").code(404).timestamp(LocalDateTime.now())
                    .message("Row " + id + " not found.").build());
        }
        return ResponseEntity.status(204).build();
    }

    private static ApiResponse<CarrierShippingLimitResponse> ok(String msg, CarrierShippingLimitResponse dto) {
        return ApiResponse.<CarrierShippingLimitResponse>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .message(msg).data(dto).build();
    }

    /**
     * 404 for missing rows — no dedicated ErrorCode enum value exists for
     * a generic admin-catalog miss (the enum only has resource-specific
     * NOT_FOUND codes like ORDER_NOT_FOUND / ACCOUNT_NOT_FOUND). Sprint 52
     * follow-up: leaving errorCode null keeps the enum untouched; a future
     * sprint can add a CARRIER_LIMIT_NOT_FOUND code without breaking
     * clients that already branch on the HTTP status.
     */
    private static ResponseEntity<ApiResponse<CarrierShippingLimitResponse>> notFound(Long id) {
        return ResponseEntity.status(404).body(ApiResponse.<CarrierShippingLimitResponse>builder()
                .status("ERROR").code(404).timestamp(LocalDateTime.now())
                .message("Row " + id + " not found.").build());
    }
}
