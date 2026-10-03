package com.multiship.backend.service;

import com.multiship.backend.model.ClientAllowedPackage;
import com.multiship.backend.model.ClientShipviaCodeMapPackage;
import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.model.ServicePackage;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.ClientAllowedPackageRepository;
import com.multiship.backend.repository.ClientServiceCodeMapRepository;
import com.multiship.backend.repository.ClientShipviaCodeMapPackageRepository;
import com.multiship.backend.repository.ClientShipviaCodeMapRepository;
import com.multiship.backend.repository.PackagePresetRepository;
import com.multiship.backend.repository.ServicePackageRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.repository.WarehouseRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F5-B regression tests for the client-scoped package selector
 * {@link ShippingConfigService#pickPackageForClient(Long, Long, String, java.math.BigDecimal)}.
 *
 * <p>V126 merge note — per-lane package restrictions moved from
 * ShipMethodRulePackage onto the sidecar {@link ClientShipviaCodeMapPackage}
 * table ({@code map_id, preset_id}). The {@code ruleId} parameter is now a
 * {@code ClientShipviaCodeMap} id.
 *
 * <p>Contract locked with the user:
 * <ol>
 *   <li>Candidate pool = ServicePackage ∩ (ClientAllowedPackage OR client-owned) ∩
 *       (per-lane package restrictions if any) ∩ enabled ∩ fits-by-weight</li>
 *   <li>Empty candidates → {@link ShippingConfigService.PackageResolutionException}
 *       with a diagnostic message naming the failed constraint</li>
 *   <li>No silent fallback to a global default preset (pre-fix behavior)</li>
 *   <li>Fit algorithm unchanged — minimize billable weight, ties on sort_order</li>
 * </ol>
 */
class ShippingConfigServicePickPackageForClientTest {

    private ShippingServiceRepository serviceRepository;
    private PackagePresetRepository presetRepository;
    private ServicePackageRepository servicePackageRepository;
    private ClientShipviaCodeMapRepository clientShipviaAliasRepository;
    private ClientShipviaCodeMapPackageRepository clientShipviaMapPackageRepository;
    private ClientServiceCodeMapRepository clientServiceAliasRepository;
    private ClientAllowedPackageRepository clientAllowedPackageRepository;
    private WarehouseRepository warehouseRepository;
    private CarrierAccountRefRepository carrierAccountRefRepository;
    private ApplicationEventPublisher eventPublisher;

    private ShippingConfigService service;

    @BeforeEach
    void setUp() {
        serviceRepository = mock(ShippingServiceRepository.class);
        presetRepository = mock(PackagePresetRepository.class);
        servicePackageRepository = mock(ServicePackageRepository.class);
        clientShipviaAliasRepository = mock(ClientShipviaCodeMapRepository.class);
        clientShipviaMapPackageRepository = mock(ClientShipviaCodeMapPackageRepository.class);
        clientServiceAliasRepository = mock(ClientServiceCodeMapRepository.class);
        clientAllowedPackageRepository = mock(ClientAllowedPackageRepository.class);
        warehouseRepository = mock(WarehouseRepository.class);
        carrierAccountRefRepository = mock(CarrierAccountRefRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        service = new ShippingConfigService(
                serviceRepository, presetRepository, servicePackageRepository,
                clientShipviaAliasRepository, clientShipviaMapPackageRepository,
                clientServiceAliasRepository, clientAllowedPackageRepository,
                warehouseRepository, List.<CarrierConnector>of(),
                carrierAccountRefRepository, eventPublisher);
    }

    // ===== helpers =====

    private static PackagePreset preset(Long id, String name, BigDecimal maxWeight,
                                        String ownerType, String ownerClientCode) {
        return PackagePreset.builder()
                .id(id).name(name)
                .kind("CUSTOM").carrier("UPS")
                .ownerType(ownerType).ownerClientCode(ownerClientCode)
                .enabled(true)
                .maxWeight(maxWeight)
                .length(BigDecimal.valueOf(10))
                .width(BigDecimal.valueOf(10))
                .height(BigDecimal.valueOf(10))
                .sortOrder(0)
                .build();
    }

    private static ShippingService service(Long id) {
        return ShippingService.builder()
                .id(id).carrier("UPS").serviceCode("GROUND").name("UPS Ground")
                .enabled(true).build();
    }

    private static ServicePackage svcPkg(Long serviceId, Long presetId) {
        return ServicePackage.builder().serviceId(serviceId).presetId(presetId).build();
    }

    private static ClientAllowedPackage allow(String clientCode, Long presetId) {
        return ClientAllowedPackage.builder()
                .clientCode(clientCode).presetId(presetId).build();
    }

    private static ClientShipviaCodeMapPackage mapAllow(Long mapId, Long presetId) {
        ClientShipviaCodeMapPackage r = new ClientShipviaCodeMapPackage();
        r.setMapId(mapId);
        r.setPresetId(presetId);
        return r;
    }

    // ===== happy path =====

    @Test
    void picksAllowedPreset_whenServiceLinkAndClientAllowlistBothInclude() {
        PackagePreset p = preset(10L, "Standard Box", new BigDecimal("5"),
                PackagePreset.OWNER_PLATFORM, null);
        when(serviceRepository.findById(1L)).thenReturn(Optional.of(service(1L)));
        when(servicePackageRepository.findByServiceId(1L)).thenReturn(List.of(svcPkg(1L, 10L)));
        when(presetRepository.findById(10L)).thenReturn(Optional.of(p));
        when(clientAllowedPackageRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of(allow("ACME", 10L)));

        ShippingConfigService.PickedPackage picked = service.pickPackageForClient(
                1L, null, "ACME", new BigDecimal("2"));

        assertEquals(10L, picked.preset().getId());
    }

    // ===== client-owned auto-allow =====

    @Test
    void clientOwnedPresetIsAutoAllowed_evenWithoutClientAllowedPackageRow() {
        PackagePreset owned = preset(20L, "ACME Custom Box", new BigDecimal("5"),
                PackagePreset.OWNER_CLIENT, "ACME");
        when(serviceRepository.findById(1L)).thenReturn(Optional.of(service(1L)));
        when(servicePackageRepository.findByServiceId(1L)).thenReturn(List.of(svcPkg(1L, 20L)));
        when(presetRepository.findById(20L)).thenReturn(Optional.of(owned));
        when(clientAllowedPackageRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of());

        ShippingConfigService.PickedPackage picked = service.pickPackageForClient(
                1L, null, "ACME", new BigDecimal("2"));

        assertEquals(20L, picked.preset().getId());
    }

    @Test
    void clientOwnedPreset_isNotAutoAllowedForOtherClients() {
        PackagePreset ownedByAcme = preset(20L, "ACME Custom", new BigDecimal("5"),
                PackagePreset.OWNER_CLIENT, "ACME");
        when(serviceRepository.findById(1L)).thenReturn(Optional.of(service(1L)));
        when(servicePackageRepository.findByServiceId(1L)).thenReturn(List.of(svcPkg(1L, 20L)));
        when(presetRepository.findById(20L)).thenReturn(Optional.of(ownedByAcme));
        when(clientAllowedPackageRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("MEGA"))
                .thenReturn(List.of());

        ShippingConfigService.PackageResolutionException ex = assertThrows(
                ShippingConfigService.PackageResolutionException.class,
                () -> service.pickPackageForClient(1L, null, "MEGA", new BigDecimal("2")));
        assertTrue(ex.getMessage().contains("MEGA"),
                "message must name the client that has no eligible packages; got: "
                        + ex.getMessage());
    }

    // ===== per-lane restrictions (ClientShipviaCodeMapPackage) =====

    @Test
    void ruleRestriction_narrowsCandidatePool() {
        PackagePreset p10 = preset(10L, "Small", new BigDecimal("5"),
                PackagePreset.OWNER_PLATFORM, null);
        PackagePreset p11 = preset(11L, "Medium", new BigDecimal("10"),
                PackagePreset.OWNER_PLATFORM, null);
        when(serviceRepository.findById(1L)).thenReturn(Optional.of(service(1L)));
        when(servicePackageRepository.findByServiceId(1L))
                .thenReturn(List.of(svcPkg(1L, 10L), svcPkg(1L, 11L)));
        when(presetRepository.findById(10L)).thenReturn(Optional.of(p10));
        when(presetRepository.findById(11L)).thenReturn(Optional.of(p11));
        when(clientAllowedPackageRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of(allow("ACME", 10L), allow("ACME", 11L)));
        when(clientShipviaMapPackageRepository.findByMapId(100L))
                .thenReturn(List.of(mapAllow(100L, 11L)));

        ShippingConfigService.PickedPackage picked = service.pickPackageForClient(
                1L, 100L, "ACME", new BigDecimal("2"));

        assertEquals(11L, picked.preset().getId(),
                "map 100 restricts to preset 11; even though 10 would billable-fit "
                        + "cheaper, it's excluded by the restriction");
    }

    @Test
    void emptyRuleRestriction_isTreatedAsUnrestricted() {
        PackagePreset p = preset(10L, "Standard", new BigDecimal("5"),
                PackagePreset.OWNER_PLATFORM, null);
        when(serviceRepository.findById(1L)).thenReturn(Optional.of(service(1L)));
        when(servicePackageRepository.findByServiceId(1L)).thenReturn(List.of(svcPkg(1L, 10L)));
        when(presetRepository.findById(10L)).thenReturn(Optional.of(p));
        when(clientAllowedPackageRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of(allow("ACME", 10L)));
        when(clientShipviaMapPackageRepository.findByMapId(100L)).thenReturn(List.of());

        ShippingConfigService.PickedPackage picked = service.pickPackageForClient(
                1L, 100L, "ACME", new BigDecimal("2"));

        assertEquals(10L, picked.preset().getId());
    }

    // ===== throw-on-empty =====

    @Test
    void noAllowedPackages_throwsWithClientHint() {
        PackagePreset p = preset(10L, "Standard", new BigDecimal("5"),
                PackagePreset.OWNER_PLATFORM, null);
        when(serviceRepository.findById(1L)).thenReturn(Optional.of(service(1L)));
        when(servicePackageRepository.findByServiceId(1L)).thenReturn(List.of(svcPkg(1L, 10L)));
        when(presetRepository.findById(10L)).thenReturn(Optional.of(p));
        when(clientAllowedPackageRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of());

        ShippingConfigService.PackageResolutionException ex = assertThrows(
                ShippingConfigService.PackageResolutionException.class,
                () -> service.pickPackageForClient(1L, null, "ACME", new BigDecimal("2")));
        assertTrue(ex.getMessage().contains("ACME"),
                "message must name the client with no allowlist; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("allow") || ex.getMessage().contains("Allow"),
                "message must point at the allowlist as the problem; got: " + ex.getMessage());
    }

    @Test
    void noServicePackageLinks_throwsWithServiceHint() {
        when(serviceRepository.findById(1L)).thenReturn(Optional.of(service(1L)));
        when(servicePackageRepository.findByServiceId(1L)).thenReturn(List.of());
        when(clientAllowedPackageRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc(anyString()))
                .thenReturn(List.of(allow("ACME", 10L)));

        ShippingConfigService.PackageResolutionException ex = assertThrows(
                ShippingConfigService.PackageResolutionException.class,
                () -> service.pickPackageForClient(1L, null, "ACME", new BigDecimal("2")));
        assertTrue(ex.getMessage().contains("no linked packages")
                || ex.getMessage().toLowerCase().contains("shipping catalog"),
                "message must point at the missing ServicePackage rows; got: "
                        + ex.getMessage());
    }

    @Test
    void weightExceedsAllAllowed_throwsWithWeightHint() {
        PackagePreset small = preset(10L, "Tiny", new BigDecimal("5"),
                PackagePreset.OWNER_PLATFORM, null);
        when(serviceRepository.findById(1L)).thenReturn(Optional.of(service(1L)));
        when(servicePackageRepository.findByServiceId(1L)).thenReturn(List.of(svcPkg(1L, 10L)));
        when(presetRepository.findById(10L)).thenReturn(Optional.of(small));
        when(clientAllowedPackageRepository
                .findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of(allow("ACME", 10L)));

        ShippingConfigService.PackageResolutionException ex = assertThrows(
                ShippingConfigService.PackageResolutionException.class,
                () -> service.pickPackageForClient(1L, null, "ACME", new BigDecimal("10")));
        assertTrue(ex.getMessage().contains("carry") || ex.getMessage().contains("weight")
                        || ex.getMessage().contains("maxWeight"),
                "message must mention the weight-fit failure; got: " + ex.getMessage());
    }

    // ===== defensive input validation =====

    @Test
    void blankClientCode_throwsImmediately() {
        assertThrows(ShippingConfigService.PackageResolutionException.class,
                () -> service.pickPackageForClient(1L, null, "  ", new BigDecimal("1")));
    }

    @Test
    void nullClientCode_throwsImmediately() {
        assertThrows(ShippingConfigService.PackageResolutionException.class,
                () -> service.pickPackageForClient(1L, null, null, new BigDecimal("1")));
    }

    @Test
    void nullServiceId_throwsImmediately() {
        assertThrows(ShippingConfigService.PackageResolutionException.class,
                () -> service.pickPackageForClient(null, null, "ACME", new BigDecimal("1")));
    }
}
