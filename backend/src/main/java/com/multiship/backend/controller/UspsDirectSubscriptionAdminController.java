package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.UspsDirectSubscriptionDTO;
import com.multiship.backend.dto.UspsDirectSubscriptionRequest;
import com.multiship.backend.model.UspsDirectSubscription;
import com.multiship.backend.service.carriers.usps.UspsDirectSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * USPS_DIRECT PR-B — admin surface for managing USPS Subscriptions-
 * Tracking v3 push subscriptions. Distinct from
 * {@link WebhookSubscriptionAdminController} (which manages OUTBOUND
 * subscriptions our tenants own on their side) — this controller is
 * INBOUND: it lets platform admins register subscriptions with USPS so
 * USPS pushes tracking events at us.
 *
 * <p>All endpoints require ADMIN. Every write path talks to USPS' REST
 * API first + persists locally only after a successful response, so a
 * failed USPS call never leaves an orphan local row.
 */
@Tag(name = "USPS Direct subscriptions (admin)",
        description = "USPS_DIRECT PR-B — admin surface for USPS Subscriptions-Tracking v3 push subscriptions")
@RestController
@RequestMapping("/api/v1/admin/usps-direct/subscriptions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UspsDirectSubscriptionAdminController {

    private final UspsDirectSubscriptionService service;

    @Operation(summary = "Create a new USPS push subscription")
    @PostMapping
    public ResponseEntity<ApiResponse<UspsDirectSubscriptionDTO>> create(
            @RequestBody UspsDirectSubscriptionRequest body) {
        try {
            UspsDirectSubscription saved = service.create(body);
            return ResponseEntity.status(HttpStatus.CREATED).body(
                    ApiResponse.<UspsDirectSubscriptionDTO>builder()
                            .status("success")
                            .code(HttpStatus.CREATED.value())
                            .message("Subscription created")
                            .data(UspsDirectSubscriptionDTO.from(saved))
                            .build());
        } catch (IllegalArgumentException validation) {
            return bad(validation.getMessage());
        } catch (IllegalStateException upstream) {
            // Platform-cred missing OR USPS rejected. Surface as 502 so
            // the caller can distinguish "your payload is bad" (400) from
            // "USPS said no / you haven't configured us" (502).
            return upstreamFailure(upstream.getMessage());
        }
    }

    @Operation(summary = "List USPS push subscriptions (ACTIVE only)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<UspsDirectSubscriptionDTO>>> list() {
        List<UspsDirectSubscriptionDTO> data = service.list().stream()
                .map(UspsDirectSubscriptionDTO::from)
                .toList();
        return ok(data);
    }

    @Operation(summary = "Read a single USPS push subscription by local id")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UspsDirectSubscriptionDTO>> getById(@PathVariable Long id) {
        Optional<UspsDirectSubscription> row = service.getById(id);
        return row.map(r -> ok(UspsDirectSubscriptionDTO.from(r)))
                .orElseGet(UspsDirectSubscriptionAdminController::notFound);
    }

    @Operation(summary = "Delete a USPS push subscription (against USPS + soft-delete locally)")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        try {
            Optional<UspsDirectSubscription> removed = service.delete(id);
            if (removed.isEmpty()) return notFound();
            return ok(null);
        } catch (IllegalStateException upstream) {
            return upstreamFailure(upstream.getMessage());
        }
    }

    // ============================================================
    // ApiResponse envelope helpers — mirror WebhookSubscriptionAdminController.
    // ============================================================

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.<T>builder()
                .status("success").code(HttpStatus.OK.value()).data(data).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> bad(String message) {
        return ResponseEntity.badRequest().body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.BAD_REQUEST.value())
                .message(message).errorCode(ErrorCode.VALIDATION_ERROR.name()).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.NOT_FOUND.value())
                .message("subscription not found")
                .errorCode(ErrorCode.VALIDATION_ERROR.name()).build());
    }

    private static <T> ResponseEntity<ApiResponse<T>> upstreamFailure(String message) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.<T>builder()
                .status("error").code(HttpStatus.BAD_GATEWAY.value())
                .message(message)
                .errorCode(ErrorCode.CARRIER_FAILURE.name()).build());
    }
}
