package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.ShipmentGroupDetailDTO;
import com.multiship.backend.dto.ShipmentGroupSummaryDTO;
import com.multiship.backend.model.ShipmentGroup;
import com.multiship.backend.repository.ShipmentGroupRepository;
import com.multiship.backend.repository.ShipmentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

import com.multiship.backend.config.OrderAccessEvaluator;

/**
 * Sprint 47 PR3 — read endpoints for the split-shipment groups introduced
 * in PR1/PR2. Detail view fetches the group + its children; list view
 * filters by client OR by parent order.
 *
 * <p>Auth: operators (ADMIN / USER) see everything; TENANT users only see
 * groups whose {@code clientCode} matches their tenant (the codebase-wide
 * "tenant id == uppercase username" convention — see
 * {@link OrderAccessEvaluator}). Because the detail endpoint has no
 * client scope in the URL, authorization runs AFTER loading the group.
 */
@Tag(name = "Shipment groups",
        description = "Read the multi-warehouse shipment groups persisted by /orders/multi-warehouse-label")
@RestController
@RequestMapping("/api/v1/shipment-groups")
// Sprint 49 Tier 1: @CrossOrigin("*") removed — SecurityConfig applies restrictive CORS globally.
@RequiredArgsConstructor
public class ShipmentGroupController {

    private final ShipmentGroupRepository groupRepository;
    private final ShipmentRepository shipmentRepository;
    private final OrderAccessEvaluator orderAccess;

    @Operation(summary = "Get one shipment group with its child shipments",
            description = "Returns the group header plus every child Shipment ordered by id. " +
                    "TENANT callers can only fetch groups for their own client.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ShipmentGroupDetailDTO>> getById(
            @PathVariable Long id, Authentication authentication) {
        ShipmentGroup group = groupRepository.findById(id).orElse(null);
        if (group == null) {
            return notFound("Shipment group " + id + " was not found.");
        }
        if (!orderAccess.canViewTenant(authentication, group.getClientCode())) {
            return forbidden();
        }
        ShipmentGroupDetailDTO body = ShipmentGroupDetailDTO.from(
                group, shipmentRepository.findByGroupIdOrderByIdAsc(id));
        return ok(body, "Shipment group loaded.");
    }

    /**
     * List groups scoped either by {@code clientCode} (any authorised
     * caller) or by {@code orderNo} (operators only — the endpoint has no
     * pre-loaded client to check the tenant against). Exactly one of the
     * two must be supplied.
     */
    @Operation(summary = "List shipment groups",
            description = "Filter by clientCode (any authorised caller in scope) or by orderNo (operators only). " +
                    "Exactly one of the two must be supplied.")
    @PreAuthorize("(#clientCode != null and ((hasAnyRole('ADMIN','USER') and @accessScope.canAccessTenant(authentication, #clientCode)) or @orderAccess.canViewTenant(authentication, #clientCode))) " +
            "or (#orderNo != null and hasAnyRole('ADMIN','USER'))")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ShipmentGroupSummaryDTO>>> list(
            @Parameter(description = "Owning client — required for TENANT callers")
            @RequestParam(required = false) String clientCode,
            @Parameter(description = "Parent order # (operators only)")
            @RequestParam(required = false) Integer orderNo) {

        if ((clientCode == null || clientCode.isBlank()) && orderNo == null) {
            return listFailure(HttpStatus.BAD_REQUEST,
                    "Supply exactly one of clientCode or orderNo.");
        }
        if (clientCode != null && !clientCode.isBlank() && orderNo != null) {
            return listFailure(HttpStatus.BAD_REQUEST,
                    "Supply exactly one of clientCode or orderNo, not both.");
        }

        List<ShipmentGroup> groups = clientCode != null && !clientCode.isBlank()
                ? groupRepository.findByClientCodeIgnoreCaseOrderByCreatedAtDesc(clientCode.trim())
                : groupRepository.findByOrderNo(orderNo);
        List<ShipmentGroupSummaryDTO> body = groups.stream()
                .map(ShipmentGroupSummaryDTO::from).toList();
        return ResponseEntity.ok(ApiResponse.<List<ShipmentGroupSummaryDTO>>builder()
                .status("success").code(200)
                .message(body.size() + " group" + (body.size() == 1 ? "" : "s") + " found.")
                .timestamp(LocalDateTime.now())
                .data(body).build());
    }

    // ===== envelope helpers =====

    private static ResponseEntity<ApiResponse<ShipmentGroupDetailDTO>> ok(
            ShipmentGroupDetailDTO body, String message) {
        return ResponseEntity.ok(ApiResponse.<ShipmentGroupDetailDTO>builder()
                .status("success").code(200).message(message)
                .timestamp(LocalDateTime.now()).data(body).build());
    }

    private static ResponseEntity<ApiResponse<ShipmentGroupDetailDTO>> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.<ShipmentGroupDetailDTO>builder()
                        .status("error").code(HttpStatus.NOT_FOUND.value())
                        .errorCode(ErrorCode.SHIPMENT_GROUP_NOT_FOUND.name()).message(message)
                        .timestamp(LocalDateTime.now()).build());
    }

    private static ResponseEntity<ApiResponse<ShipmentGroupDetailDTO>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiResponse.<ShipmentGroupDetailDTO>builder()
                        .status("error").code(HttpStatus.FORBIDDEN.value())
                        .errorCode(ErrorCode.FORBIDDEN.name())
                        .message("Not authorised to view this shipment group.")
                        .timestamp(LocalDateTime.now()).build());
    }

    private static ResponseEntity<ApiResponse<List<ShipmentGroupSummaryDTO>>> listFailure(
            HttpStatus status, String message) {
        return ResponseEntity.status(status).body(
                ApiResponse.<List<ShipmentGroupSummaryDTO>>builder()
                        .status("error").code(status.value())
                        .errorCode(ErrorCode.VALIDATION_ERROR.name())
                        .message(message).timestamp(LocalDateTime.now()).build());
    }
}
