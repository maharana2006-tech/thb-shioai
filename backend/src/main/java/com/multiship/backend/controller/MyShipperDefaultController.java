package com.multiship.backend.controller;

import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.User;
import com.multiship.backend.repository.UserRepository;
import com.multiship.backend.service.TenantScopeEnforcer;
import com.multiship.backend.service.carrier.ShipperDefaultsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * C2 — resolved ship-from defaults for the currently signed-in user.
 * Feeds the NewShipmentPage sender prefill without exposing the admin
 * bulk-config endpoint (which is ADMIN-only under
 * {@code /tenants/{code}/settings/shipper-defaults}).
 *
 * <p>Any authenticated role. Optional {@code clientCode} query param
 * resolves for that client instead of the caller's own tenant — but
 * only if {@link TenantScopeEnforcer} allows the cross-tenant read
 * (platform ADMIN yes, scoped USER only for their own tenant).
 */
@Tag(name = "My shipper default",
        description = "Ship-from defaults resolved for the current user (or a client they can address).")
@RestController
@RequestMapping("/api/v1/me/shipper-default")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MyShipperDefaultController {

    private final UserRepository userRepo;
    private final ShipperDefaultsService shipperDefaults;
    private final TenantScopeEnforcer scope;

    @Operation(summary = "Resolve the effective ship-from block for the current user's tenant "
            + "(or a client the user can address). Returns the 8 shipper fields already merged: "
            + "tenant_settings.shipper.* overrides on top of the platform default.")
    @GetMapping
    public ResponseEntity<ApiResponse<ResolvedShipper>> resolve(
            @RequestParam(required = false) String clientCode,
            Authentication auth) {
        User me = userRepo.findByUsername(auth.getName()).orElseThrow(() ->
                new IllegalStateException("Authenticated user not found in DB: " + auth.getName()));
        String tenant;
        if (clientCode != null && !clientCode.isBlank()) {
            // requireTenantMatch throws AccessDeniedException on cross-tenant access
            // for scoped USERs; platform ADMIN passes through.
            scope.requireTenantMatch(clientCode);
            tenant = clientCode.trim();
        } else {
            tenant = me.getClientCode();
        }
        CarrierProperties.ShipperDefaults d = shipperDefaults.resolveFor(tenant);
        ResolvedShipper body = new ResolvedShipper(
                tenant,
                d.getName(),
                d.getPhone(),
                d.getAddressLine1(),
                d.getAddressLine2(),
                d.getCity(),
                d.getState(),
                d.getPostalCode(),
                d.getCountryCode());
        return ResponseEntity.ok(ApiResponse.<ResolvedShipper>builder()
                .status("SUCCESS").code(200).timestamp(LocalDateTime.now())
                .data(body).build());
    }

    public record ResolvedShipper(
            String tenantCode,
            String name,
            String phone,
            String addressLine1,
            String addressLine2,
            String city,
            String state,
            String postalCode,
            String countryCode) {}
}
