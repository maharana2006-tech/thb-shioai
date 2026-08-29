package com.multiship.backend.service.resolution;

import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.ServicePackageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Sprint 52 — guards manual-pick label generation from carrier-side
 * PACKAGINGTYPE.VALIDATION.ERROR-class rejections (order 900003 hit this
 * with FEDEX_GROUND + FEDEX_ENVELOPE — Express-only packaging on a Ground
 * service). Consults {@code service_package} as the source of truth,
 * mirroring what auto-pick has always done at
 * {@link com.multiship.backend.service.ShippingConfigService#findAllowedPackages}.
 *
 * <p>Two behaviours the guard encodes:
 * <ul>
 *   <li>{@code kind = CUSTOM} presets short-circuit as allowed. Custom
 *       boxes are shipper-defined "YOUR_PACKAGING" which every major
 *       carrier accepts on almost every service (One Rate being the
 *       narrow exception; the operator-configured
 *       {@code service_package} row for a One Rate service intentionally
 *       omits CUSTOM presets, which shows up in the FE dropdown filter
 *       shipping in PR 2). No DB round-trip needed for CUSTOM.</li>
 *   <li>{@code kind = CARRIER} presets require an explicit row in
 *       {@code service_package}. Missing row → throw. Empty pool for the
 *       service (zero rows) throws the more specific "config incomplete"
 *       code so ops sees a different fix path than "wrong package".</li>
 * </ul>
 *
 * <p>All throws are {@link ShipmentResolutionException} carrying a stable
 * {@link ErrorCode}, matching the pattern
 * {@link ShipmentResolutionService#assertPackageAllowed} already uses.
 * {@link com.multiship.backend.service.CarrierServiceImpl} maps these to
 * {@code toResolutionFailure(e)} — same 422 shape, same envelope.
 */
@Service
@RequiredArgsConstructor
public class PackagingCompatibilityGuard {

    private final ServicePackageRepository servicePackageRepository;

    /**
     * Manual-pick entry point. Throws when the picked (service, preset)
     * pair isn't in {@code service_package}, unless the preset is a
     * CUSTOM box (always allowed — see class javadoc).
     *
     * <p>Null service or null preset is a no-op — resolution earlier in
     * {@code CarrierServiceImpl.buildShipmentRequest} may leave one side
     * unresolved (e.g. connector default service kicks in only when
     * {@code service == null}); pushing the null check into the guard
     * keeps every call site symmetrical.
     */
    public void assertCompatible(ShippingService service, PackagePreset preset) {
        if (service == null || preset == null) return;
        if (PackagePreset.OWNER_PLATFORM.equals(preset.getOwnerType())
                && !"CARRIER".equalsIgnoreCase(preset.getKind())) {
            // CUSTOM PLATFORM preset — treat as YOUR_PACKAGING.
            return;
        }
        if (!"CARRIER".equalsIgnoreCase(preset.getKind())) {
            // CUSTOM CLIENT preset — same treatment.
            return;
        }

        Long serviceId = service.getId();
        Long presetId = preset.getId();
        if (servicePackageRepository.existsByServiceIdAndPresetId(serviceId, presetId)) {
            return;
        }

        long linkCount = servicePackageRepository.countByServiceId(serviceId);
        if (linkCount == 0) {
            throw new ShipmentResolutionException(
                    ErrorCode.SERVICE_HAS_NO_LINKED_PACKAGES,
                    "Service " + service.getCarrier() + " " + service.getServiceCode()
                            + " has no linked packages yet. An admin must link at least "
                            + "one preset to this service on the Shipping catalog page "
                            + "(/settings/shipping-catalog) before it can be used for a label.");
        }

        String presetLabel = preset.getCarrierPackageCode() != null
                ? preset.getCarrierPackageCode()
                : preset.getName();
        throw new ShipmentResolutionException(
                ErrorCode.PACKAGE_NOT_ALLOWED_FOR_SERVICE,
                "Package " + presetLabel + " is not allowed for service "
                        + service.getCarrier() + " " + service.getServiceCode()
                        + ". Common cause: carrier reserves this packaging for a different "
                        + "service family (e.g. FedEx envelope/pak/tube are Express-only, "
                        + "10kg/25kg boxes are international-only, One Rate boxes are One "
                        + "Rate-only). Pick a different package, or link this preset to "
                        + "the service on /settings/shipping-catalog if the pairing is intentional.");
    }

    /**
     * Read-only variant for callers that need to filter rather than throw
     * (auto-pick, FE dropdown endpoint). Returns {@code true} when the
     * pair is compatible. CUSTOM presets and null-side inputs are always
     * compatible under the same rules as {@link #assertCompatible}.
     */
    public boolean isCompatible(ShippingService service, PackagePreset preset) {
        if (service == null || preset == null) return true;
        if (!"CARRIER".equalsIgnoreCase(preset.getKind())) return true;
        return servicePackageRepository.existsByServiceIdAndPresetId(service.getId(), preset.getId());
    }
}
