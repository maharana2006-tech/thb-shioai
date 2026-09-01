package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.model.ShipViaMapping;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.service.ShippingConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Settings master data: carrier service catalog, ERP ship-via mapping, package presets. */
@Tag(name = "Shipping config", description = "Service catalog + ship-via mapping + package presets")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ShippingConfigController {

    private final ShippingConfigService service;

    @Operation(summary = "Service catalog with ship-via mappings (optionally filtered by origin country)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/shipping-services")
    public ResponseEntity<ApiResponse<Map<String, Object>>> catalog(
            @RequestParam(name = "originCountry", required = false) String originCountry) {
        ApiResponse<Map<String, Object>> r = service.catalog(originCountry);
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Sync a carrier's available services for an origin country from its availability API",
            description = "Optional accountId pins the sync to a specific verified account (platform or client). "
                    + "That account's environment (SANDBOX/PRODUCTION) routes the availability call to the "
                    + "matching carrier host. When accountId is null the sync falls back to the first "
                    + "platform account with credentials — matches pre-Sprint-51 behaviour.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/shipping-services/sync")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sync(
            @RequestParam String carrier,
            @RequestParam(name = "originCountry", required = false, defaultValue = "US") String originCountry,
            @RequestParam(name = "accountId", required = false) Long accountId) {
        ApiResponse<Map<String, Object>> r = service.syncFromCarrier(carrier, originCountry, accountId);
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Enable/disable one service")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PatchMapping("/shipping-services/{id}")
    public ResponseEntity<ApiResponse<ShippingService>> setEnabled(
            @PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        ApiResponse<ShippingService> r = service.setServiceEnabled(id, Boolean.TRUE.equals(body.get("enabled")));
        return ResponseEntity.status(r.getCode()).body(r);
    }

    /**
     * Sprint 52 PR X — toggle whether this service accepts carrier-branded
     * packaging. Body: {@code {"brandedPackagingAllowed": true|false}}.
     * When false, the settings UI filters branded presets out of the link
     * modal and the ShippingServicesPage badge shifts from amber warning
     * to a neutral "CUSTOM only" marker. See ShippingService.branded
     * PackagingAllowed javadoc for the full contract.
     */
    @Operation(summary = "Toggle whether a service accepts carrier-branded packaging (Sprint 52 PR X)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PatchMapping("/shipping-services/{id}/branded-packaging")
    public ResponseEntity<ApiResponse<ShippingService>> setBrandedPackagingAllowed(
            @PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        ApiResponse<ShippingService> r = service.setBrandedPackagingAllowed(
                id, Boolean.TRUE.equals(body.get("brandedPackagingAllowed")));
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Create/update a ship-method rule (client + destination aware)",
            description = "Optional allowedPresetIds sets the rule's default package options; empty = unrestricted at the rule level.")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PutMapping("/ship-method-rules")
    public ResponseEntity<ApiResponse<ShipViaMapping>> upsertRule(@RequestBody ShipViaMapping body) {
        ApiResponse<ShipViaMapping> r = service.upsertRule(
                body.getId(), body.getShipviaCd(), body.getClientCode(),
                body.getDestType(), body.getDestValue(), body.getServiceId(),
                body.getAllowedPresetIds(), body.getWarehouseIds());
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Delete a ship-method rule")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @DeleteMapping("/ship-method-rules/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRule(@PathVariable Long id) {
        ApiResponse<Void> r = service.deleteRule(id);
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Preview cascade impact of deleting a ship-method rule (audit #297)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/ship-method-rules/{id}/cascade-preview")
    public ResponseEntity<ApiResponse<com.multiship.backend.dto.RuleCascadePreviewDTO>> previewRuleDelete(@PathVariable Long id) {
        ApiResponse<com.multiship.backend.dto.RuleCascadePreviewDTO> r = service.previewRuleDelete(id);
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Replace a service's allowed packages (+ discount %)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PutMapping("/shipping-services/{id}/packages")
    public ResponseEntity<ApiResponse<java.util.List<com.multiship.backend.model.ServicePackage>>> setServicePackages(
            @PathVariable Long id, @RequestBody java.util.List<com.multiship.backend.model.ServicePackage> links) {
        ApiResponse<java.util.List<com.multiship.backend.model.ServicePackage>> r = service.setServicePackages(id, links);
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "List package presets")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/package-presets")
    public ResponseEntity<ApiResponse<List<PackagePreset>>> listPresets() {
        ApiResponse<List<PackagePreset>> r = service.listPresets();
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Sync a carrier's predefined packaging for an origin country",
            description = "Optional accountId pins the sync to a specific verified account (platform or client). "
                    + "That account's environment (SANDBOX/PRODUCTION) routes the packaging call to the "
                    + "matching carrier host. When accountId is null the sync falls back to the first "
                    + "platform account with credentials — matches pre-Sprint-51 behaviour.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/package-presets/sync")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncPackages(
            @RequestParam String carrier,
            @RequestParam(name = "originCountry", required = false, defaultValue = "US") String originCountry,
            @RequestParam(name = "accountId", required = false) Long accountId) {
        ApiResponse<Map<String, Object>> r = service.syncPackagesFromCarrier(carrier, originCountry, accountId);
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Create a package preset")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PostMapping("/package-presets")
    public ResponseEntity<ApiResponse<PackagePreset>> createPreset(@RequestBody PackagePreset request) {
        ApiResponse<PackagePreset> r = service.savePreset(null, request);
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Update a package preset")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PutMapping("/package-presets/{id}")
    public ResponseEntity<ApiResponse<PackagePreset>> updatePreset(
            @PathVariable Long id, @RequestBody PackagePreset request) {
        ApiResponse<PackagePreset> r = service.savePreset(id, request);
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Make a preset the default package")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PutMapping("/package-presets/{id}/default")
    public ResponseEntity<ApiResponse<PackagePreset>> setDefault(@PathVariable Long id) {
        ApiResponse<PackagePreset> r = service.setDefaultPreset(id);
        return ResponseEntity.status(r.getCode()).body(r);
    }

    @Operation(summary = "Delete a package preset")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @DeleteMapping("/package-presets/{id}")
    public ResponseEntity<ApiResponse<Void>> deletePreset(@PathVariable Long id) {
        ApiResponse<Void> r = service.deletePreset(id);
        return ResponseEntity.status(r.getCode()).body(r);
    }
}
