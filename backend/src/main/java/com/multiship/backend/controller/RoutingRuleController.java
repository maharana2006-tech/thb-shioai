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

    @Operation(summary = "Create or update a routing rule",
            description = "Audit R2 #351 — 409 ROUTING_RULE_CONCURRENT_EDIT when another admin "
                    + "updated the same rule since the caller last read it; refresh + retry.")
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
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException race) {
            // Audit R2 #351 — another admin bumped the version between our
            // read + save. Signal 409 with a dedicated code so the FE can
            // refresh + prompt retry rather than silently discarding the
            // caller's edits.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.<RoutingRuleDTO>builder()
                    .status("error").code(HttpStatus.CONFLICT.value())
                    .message("This routing rule was changed by another admin — "
                            + "refresh the page and re-apply your edits.")
                    .errorCode(com.multiship.backend.dto.ErrorCode.ROUTING_RULE_CONCURRENT_EDIT.name())
                    .build());
        }
    }

    @Operation(summary = "Delete a routing rule",
            description = "Audit R2 #350 — matches save's role gate + tenant guard so a USER "
                    + "who can create a rule for their own tenant can also delete one. Was "
                    + "ADMIN-only pre-fix, forcing operators to editing active=false as a workaround.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER') and @accessScope.canAccessTenant(authentication, #clientCode)")
    @DeleteMapping("/{ruleId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String clientCode, @PathVariable Long ruleId) {
        try {
            service.delete(clientCode, ruleId);
        } catch (IllegalArgumentException crossTenant) {
            // Audit B5 — cross-tenant delete now surfaces as 400 with the
            // real reason instead of a silent 200.
            return badRequest(crossTenant.getMessage(), "VALIDATION_FAILED");
        }
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

    @SuppressWarnings("SameParameterValue")
    private static <T> ResponseEntity<ApiResponse<T>> badRequest(String message, String errorCode) {
        return ResponseEntity.badRequest().body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.BAD_REQUEST.value())
                .message(message).errorCode(errorCode).build());
    }
}
