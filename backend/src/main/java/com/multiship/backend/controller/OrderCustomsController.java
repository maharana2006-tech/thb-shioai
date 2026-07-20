package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.OrderCustomsDTO;
import com.multiship.backend.dto.OrderCustomsUpsertRequest;
import com.multiship.backend.service.CustomsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customs declaration for an international order (importer + line items). */
@Tag(name = "Orders", description = "Customs declaration attached to an order")
@RestController
@RequestMapping("/api/v1/orders/{orderNo}/customs")
@RequiredArgsConstructor
public class OrderCustomsController {

    private final CustomsService customsService;

    @Operation(summary = "Get an order's customs declaration",
            description = "Returns the importer of record and declared line items; data is null when none exists yet.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping
    public ResponseEntity<ApiResponse<OrderCustomsDTO>> getCustoms(@PathVariable String orderNo) {
        ApiResponse<OrderCustomsDTO> response = customsService.getCustoms(orderNo);
        return ResponseEntity.status(response.getCode()).body(response);
    }

    @Operation(summary = "Create or replace an order's customs declaration",
            description = "Line items are replaced wholesale. Required for international label generation.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PutMapping
    public ResponseEntity<ApiResponse<OrderCustomsDTO>> upsertCustoms(
            @PathVariable String orderNo,
            @Valid @RequestBody OrderCustomsUpsertRequest request) {
        ApiResponse<OrderCustomsDTO> response = customsService.upsertCustoms(orderNo, request);
        return ResponseEntity.status(response.getCode()).body(response);
    }
}
