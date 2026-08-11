package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.RoutingEvaluationRequest;
import com.multiship.backend.dto.RoutingEvaluationResult;
import com.multiship.backend.dto.RoutingRuleDTO;
import com.multiship.backend.model.RoutingRule;
import com.multiship.backend.service.RoutingRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Sprint 44 — per-client routing rules CRUD + dry-run.
 */
@Tag(name = "Routing rules",
        description = "Post-rate-shop rerouting / blocking rules per client (Sprint 44)")
@RestController
@RequestMapping("/api/v1/clients/{clientCode}/routing-rules")
@RequiredArgsConstructor
public class RoutingRuleController {

    private final RoutingRuleService service;

    @Operation(summary = "List a client's routing rules ordered by priority")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #clientCode)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<RoutingRuleDTO>>> list(@PathVariable String clientCode) {
        List<RoutingRuleDTO> data = service.listForClient(clientCode).stream()
                .map(RoutingRuleDTO::from).toList();
        return ok(data, "Rules loaded");
    }

    @Operation(summary = "Create or update a routing rule")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #clientCode)")
    @PostMapping
    public ResponseEntity<ApiResponse<RoutingRuleDTO>> save(
            @PathVariable String clientCode,
            @RequestBody RoutingRuleDTO body) {
        try {
            body.setClientCode(clientCode);
            RoutingRule saved = service.save(body.toEntity());
            HttpStatus code = body.getId() == null ? HttpStatus.CREATED : HttpStatus.OK;
            return ResponseEntity.status(code).body(ApiResponse.<RoutingRuleDTO>builder()
                    .status("success").code(code.value())
                    .message("Rule saved")
                    .data(RoutingRuleDTO.from(saved))
                    .build());
        } catch (IllegalArgumentException validation) {
            return badRequest(validation.getMessage(), "VALIDATION_FAILED");
        }
    }

    @Operation(summary = "Delete a routing rule")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{ruleId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String clientCode, @PathVariable Long ruleId) {
        service.delete(clientCode, ruleId);
        return ok(null, "Rule deleted");
    }

    @Operation(summary = "Dry-run: preview which rule fires for a synthetic shipment")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #clientCode)")
    @PostMapping("/dry-run")
    public ResponseEntity<ApiResponse<RoutingEvaluationResult>> dryRun(
            @PathVariable String clientCode,
            @RequestBody RoutingEvaluationRequest request) {
        RoutingEvaluationResult result = service.evaluate(clientCode, request);
        return ok(result, "Dry-run complete");
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data, String message) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("success").code(HttpStatus.OK.value())
                .message(message).data(data).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> badRequest(String message, String errorCode) {
        return ResponseEntity.badRequest().body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.BAD_REQUEST.value())
                .message(message).errorCode(errorCode).build());
    }
}
