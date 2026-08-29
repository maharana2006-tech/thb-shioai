package com.multiship.backend.service.resolution;

import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.ServicePackageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackagingCompatibilityGuardTest {

    private ServicePackageRepository repo;
    private PackagingCompatibilityGuard guard;

    @BeforeEach
    void setUp() {
        repo = mock(ServicePackageRepository.class);
        guard = new PackagingCompatibilityGuard(repo);
    }

    // ─── Null-side inputs are no-ops (unresolved service or preset) ──────

    @Test
    void nullService_noThrow_noRepoHit() {
        guard.assertCompatible(null, preset(2L, "CARRIER", "FEDEX_ENVELOPE"));
        verify(repo, never()).existsByServiceIdAndPresetId(anyLong(), anyLong());
    }

    @Test
    void nullPreset_noThrow_noRepoHit() {
        guard.assertCompatible(service(1L, "FEDEX", "FEDEX_GROUND"), null);
        verify(repo, never()).existsByServiceIdAndPresetId(anyLong(), anyLong());
    }

    // ─── CUSTOM presets short-circuit as allowed (no repo hit) ───────────

    @Test
    void customPreset_alwaysAllowed_noRepoHit() {
        guard.assertCompatible(service(1L, "FEDEX", "FEDEX_GROUND"),
                preset(2L, "CUSTOM", null));
        verify(repo, never()).existsByServiceIdAndPresetId(anyLong(), anyLong());
    }

    // ─── CARRIER preset — linked pair → allowed ──────────────────────────

    @Test
    void carrierPreset_whenLinked_noThrow() {
        when(repo.existsByServiceIdAndPresetId(1L, 2L)).thenReturn(true);
        assertDoesNotThrow(() -> guard.assertCompatible(
                service(1L, "FEDEX", "FEDEX_2_DAY"),
                preset(2L, "CARRIER", "FEDEX_ENVELOPE")));
    }

    // ─── Regression pin for order 900003 ─────────────────────────────────

    @Test
    void fedexGround_x_fedexEnvelope_throwsPackageNotAllowedForService() {
        // Simulates the exact 900003 combination — FEDEX_GROUND + FEDEX_ENVELOPE.
        // In a well-seeded DB (V29), the service has links (e.g. maybe
        // FEDEX_10KG_BOX from the intl seed) but NOT the envelope; the guard
        // must throw the specific PACKAGE_NOT_ALLOWED_FOR_SERVICE code —
        // not the SERVICE_HAS_NO_LINKED_PACKAGES fallback (which would
        // mislead ops into thinking the service is unconfigured).
        when(repo.existsByServiceIdAndPresetId(1L, 2L)).thenReturn(false);
        when(repo.countByServiceId(1L)).thenReturn(3L); // service has other links

        ShipmentResolutionException ex = assertThrows(
                ShipmentResolutionException.class,
                () -> guard.assertCompatible(
                        service(1L, "FEDEX", "FEDEX_GROUND"),
                        preset(2L, "CARRIER", "FEDEX_ENVELOPE")));

        assertEquals(ErrorCode.PACKAGE_NOT_ALLOWED_FOR_SERVICE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("FEDEX_ENVELOPE"),
                "error must name the package code the operator picked");
        assertTrue(ex.getMessage().contains("FEDEX_GROUND"),
                "error must name the service so ops can act on it");
    }

    // ─── Empty-pool path (config incomplete) ─────────────────────────────

    @Test
    void carrierPreset_whenServiceHasZeroLinks_throwsServiceHasNoLinkedPackages() {
        // Distinct from the mismatched-pair case above — this is "admin
        // hasn't linked ANY package to this service yet". The FE routes
        // this ErrorCode to /settings/shipping-catalog with a different
        // hint than PACKAGE_NOT_ALLOWED_FOR_SERVICE.
        when(repo.existsByServiceIdAndPresetId(1L, 2L)).thenReturn(false);
        when(repo.countByServiceId(1L)).thenReturn(0L);

        ShipmentResolutionException ex = assertThrows(
                ShipmentResolutionException.class,
                () -> guard.assertCompatible(
                        service(1L, "UPS", "03"),
                        preset(2L, "CARRIER", "02")));

        assertEquals(ErrorCode.SERVICE_HAS_NO_LINKED_PACKAGES, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("shipping-catalog"),
                "empty-pool error must point ops at the catalog page");
    }

    // ─── isCompatible mirror — no-throw variant for callers that filter ──

    @Test
    void isCompatible_customPreset_returnsTrueWithoutRepoHit() {
        assertTrue(guard.isCompatible(service(1L, "FEDEX", "FEDEX_GROUND"),
                preset(2L, "CUSTOM", null)));
        verify(repo, never()).existsByServiceIdAndPresetId(anyLong(), anyLong());
    }

    @Test
    void isCompatible_carrierPreset_delegatesToRepoExists() {
        when(repo.existsByServiceIdAndPresetId(1L, 2L)).thenReturn(true);
        assertTrue(guard.isCompatible(
                service(1L, "FEDEX", "FEDEX_2_DAY"),
                preset(2L, "CARRIER", "FEDEX_ENVELOPE")));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private ShippingService service(Long id, String carrier, String code) {
        return ShippingService.builder()
                .id(id).carrier(carrier).serviceCode(code).name(code)
                .scope("BOTH").originCountry("US").build();
    }

    private PackagePreset preset(Long id, String kind, String carrierPackageCode) {
        return PackagePreset.builder()
                .id(id).name("preset-" + id).kind(kind)
                .carrierPackageCode(carrierPackageCode)
                .ownerType(PackagePreset.OWNER_PLATFORM)
                .build();
    }
}
