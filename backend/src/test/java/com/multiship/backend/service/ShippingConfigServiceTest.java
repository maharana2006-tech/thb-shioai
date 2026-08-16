package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.model.ServicePackage;
import com.multiship.backend.model.ShipViaMapping;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.PackagePresetRepository;
import com.multiship.backend.repository.ServicePackageRepository;
import com.multiship.backend.repository.ShipMethodRulePackageRepository;
import com.multiship.backend.repository.ShipMethodRuleWarehouseRepository;
import com.multiship.backend.repository.ShipViaMappingRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.repository.WarehouseRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShippingConfigService} — the backend behind
 * `/settings/shipping-catalog`. Covers the 11 public methods called from the
 * controller: {@code catalog}, {@code syncFromCarrier}, {@code setServiceEnabled},
 * {@code upsertRule}, {@code deleteRule}, {@code setServicePackages},
 * {@code listPresets}, {@code syncPackagesFromCarrier}, {@code savePreset},
 * {@code setDefaultPreset}, {@code deletePreset}.
 *
 * <p>Anti-fallback: every {@link CarrierConnector} used in a test is a
 * Mockito {@code mock(CarrierConnector.class)}. The concrete connector
 * implementations ({@code UpsConnector}, {@code FedExConnector},
 * {@code StampsConnector}, {@code DhlConnector}) are NOT constructed —
 * we control the returned {@link CarrierConnector.ServiceAvailability}
 * and {@link CarrierConnector.PackageAvailability} verbatim so no test
 * can escape to a real carrier host.
 *
 * <p>Response contract pinned: this service uses uppercase status strings
 * (`"SUCCESS"` and `"ERROR"`) unlike some other services that use lowercase.
 */
class ShippingConfigServiceTest {

    private ShippingServiceRepository serviceRepository;
    private ShipViaMappingRepository ruleRepository;
    private PackagePresetRepository presetRepository;
    private ServicePackageRepository servicePackageRepository;
    private ShipMethodRulePackageRepository rulePackageRepository;
    private ShipMethodRuleWarehouseRepository ruleWarehouseRepository;
    private WarehouseRepository warehouseRepository;
    private CarrierAccountRefRepository carrierAccountRefRepository;
    private ApplicationEventPublisher eventPublisher;
    private CarrierConnector upsMock;
    private CarrierConnector fedexMock;

    private ShippingConfigService service;

    @BeforeEach
    void setUp() {
        serviceRepository = mock(ShippingServiceRepository.class);
        ruleRepository = mock(ShipViaMappingRepository.class);
        presetRepository = mock(PackagePresetRepository.class);
        servicePackageRepository = mock(ServicePackageRepository.class);
        rulePackageRepository = mock(ShipMethodRulePackageRepository.class);
        ruleWarehouseRepository = mock(ShipMethodRuleWarehouseRepository.class);
        warehouseRepository = mock(WarehouseRepository.class);
        carrierAccountRefRepository = mock(CarrierAccountRefRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        // Anti-fallback: two mock connectors stand in for the real UPS and
        // FedEx classes. Their impls (Ups/FedEx/Stamps/DhlConnector) are
        // never instantiated so real carrier IO is impossible in these tests.
        upsMock = mock(CarrierConnector.class);
        when(upsMock.getCarrierCode()).thenReturn("UPS");
        fedexMock = mock(CarrierConnector.class);
        when(fedexMock.getCarrierCode()).thenReturn("FEDEX");

        service = new ShippingConfigService(
                serviceRepository, ruleRepository, presetRepository,
                servicePackageRepository, rulePackageRepository,
                ruleWarehouseRepository, warehouseRepository,
                List.of(upsMock, fedexMock),
                carrierAccountRefRepository, eventPublisher);
    }

    // ================ helpers ================

    private static ShippingService svc(Long id, String carrier, String code, boolean enabled) {
        ShippingService s = ShippingService.builder()
                .id(id).carrier(carrier).serviceCode(code).name(carrier + " " + code)
                .originCountry("US").enabled(enabled).build();
        return s;
    }

    private static PackagePreset preset(Long id, String name, boolean isDefault) {
        return PackagePreset.builder()
                .id(id).name(name).kind("CUSTOM").carrier("UPS").isDefault(isDefault)
                .length(java.math.BigDecimal.valueOf(10))
                .width(java.math.BigDecimal.valueOf(10))
                .height(java.math.BigDecimal.valueOf(10))
                .build();
    }

    // ================ catalog() ================

    @Test
    void catalog_noOrigin_returnsAllRowsAndPublishesNoEvent() {
        when(serviceRepository.findAllByOrderByCarrierAscSortOrderAsc())
                .thenReturn(List.of(svc(1L, "UPS", "GROUND", true)));
        when(ruleRepository.findAllByOrderByShipviaCdAsc()).thenReturn(List.of());
        when(servicePackageRepository.findAll()).thenReturn(List.of());
        when(rulePackageRepository.findAll()).thenReturn(List.of());
        when(ruleWarehouseRepository.findAll()).thenReturn(List.of());
        when(serviceRepository.findDistinctOriginCountries()).thenReturn(List.of("US"));

        ApiResponse<Map<String, Object>> r = service.catalog(null);

        assertEquals("SUCCESS", r.getStatus());
        assertEquals(200, r.getCode());
        assertNotNull(r.getData().get("services"));
        assertNotNull(r.getData().get("rules"));
        assertNotNull(r.getData().get("links"));
        verify(serviceRepository, times(1)).findAllByOrderByCarrierAscSortOrderAsc();
        verify(serviceRepository, never()).findByOriginCountryIgnoreCaseOrderByCarrierAscSortOrderAsc(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void catalog_withOrigin_narrowsByOrigin() {
        when(serviceRepository.findByOriginCountryIgnoreCaseOrderByCarrierAscSortOrderAsc("GB"))
                .thenReturn(List.of(svc(2L, "UPS", "EXPRESS", true)));
        when(ruleRepository.findAllByOrderByShipviaCdAsc()).thenReturn(List.of());
        when(servicePackageRepository.findAll()).thenReturn(List.of());
        when(rulePackageRepository.findAll()).thenReturn(List.of());
        when(ruleWarehouseRepository.findAll()).thenReturn(List.of());
        when(serviceRepository.findDistinctOriginCountries()).thenReturn(List.of("US", "GB"));

        ApiResponse<Map<String, Object>> r = service.catalog("GB");

        assertEquals("SUCCESS", r.getStatus());
        verify(serviceRepository, times(1))
                .findByOriginCountryIgnoreCaseOrderByCarrierAscSortOrderAsc("GB");
        verify(serviceRepository, never()).findAllByOrderByCarrierAscSortOrderAsc();
    }

    // ================ syncFromCarrier() ================

    @Test
    void syncFromCarrier_blankCarrier_returns422_andNeverTouchesConnector() {
        ApiResponse<Map<String, Object>> r = service.syncFromCarrier("", "US");

        assertEquals("ERROR", r.getStatus());
        assertEquals(422, r.getCode());
        // Anti-fallback: no connector call happened; no real carrier could
        // possibly have been reached.
        verify(upsMock, never()).listServices(any(), any(), any());
        verify(fedexMock, never()).listServices(any(), any(), any());
        verify(serviceRepository, never()).save(any());
    }

    @Test
    void syncFromCarrier_unknownCarrier_returns422() {
        ApiResponse<Map<String, Object>> r = service.syncFromCarrier("BOGUS", "US");

        assertEquals("ERROR", r.getStatus());
        assertEquals(422, r.getCode());
        assertTrue(r.getMessage().contains("Unknown carrier"));
        verify(upsMock, never()).listServices(any(), any(), any());
        verify(fedexMock, never()).listServices(any(), any(), any());
    }

    @Test
    void syncFromCarrier_notLive_refuses_andWritesNothing() {
        // Live-only policy: when the connector reports live=false, the
        // service refuses to persist anything (no fallback carrier data
        // ever gets imported).
        when(carrierAccountRefRepository.findPlatformAccountsByCarrier("UPS"))
                .thenReturn(List.of());
        when(upsMock.listServices(eq("US"), any(), any())).thenReturn(
                new CarrierConnector.ServiceAvailability(List.of(), false, "built-in fallback"));

        ApiResponse<Map<String, Object>> r = service.syncFromCarrier("UPS", "US");

        assertEquals("ERROR", r.getStatus());
        assertEquals(422, r.getCode());
        assertTrue(r.getMessage().contains("not verified live"));
        verify(serviceRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        // Cross-carrier isolation: FEDEX connector never touched.
        verify(fedexMock, never()).listServices(any(), any(), any());
    }

    @Test
    void syncFromCarrier_live_addsNewServices_andPublishesEvent() {
        // Seed a platform account so token resolution returns a real token.
        CarrierAccountRef acct = CarrierAccountRef.builder()
                .id(1L).carrierCode("UPS")
                .clientId("cid").clientSecret("csecret").accountNumber("ACCT-1")
                .environment("SANDBOX").active(true).build();
        when(carrierAccountRefRepository.findPlatformAccountsByCarrier("UPS"))
                .thenReturn(List.of(acct));
        when(upsMock.getAccessToken("cid", "csecret", "ACCT-1"))
                .thenReturn("live-token");
        when(upsMock.listServices("US", "live-token", "SANDBOX"))
                .thenReturn(new CarrierConnector.ServiceAvailability(
                        List.of(new CarrierConnector.ServiceOffering("GROUND", "UPS Ground", "DOMESTIC")),
                        true, "UPS Rating API"));
        when(serviceRepository.findByCarrierIgnoreCaseAndServiceCodeIgnoreCaseAndOriginCountryIgnoreCase(
                "UPS", "GROUND", "US")).thenReturn(Optional.empty());
        // Echo save() so the persisted entity flows back.
        when(serviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<Map<String, Object>> r = service.syncFromCarrier("UPS", "US");

        assertEquals("SUCCESS", r.getStatus());
        assertEquals(200, r.getCode());
        assertEquals(1, r.getData().get("added"));
        assertEquals(0, r.getData().get("updated"));
        assertEquals(true, r.getData().get("live"));
        verify(upsMock, times(1)).listServices("US", "live-token", "SANDBOX");
        verify(serviceRepository, times(1)).save(any());
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
        // Anti-fallback: no other carrier's listServices was called.
        verify(fedexMock, never()).listServices(any(), any(), any());
    }

    // ================ setServiceEnabled() ================

    @Test
    void setServiceEnabled_true_updatesAndPublishesEvent() {
        ShippingService s = svc(5L, "UPS", "GROUND", false);
        when(serviceRepository.findById(5L)).thenReturn(Optional.of(s));
        when(serviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<ShippingService> r = service.setServiceEnabled(5L, true);

        assertEquals("SUCCESS", r.getStatus());
        assertTrue(r.getData().isEnabled());
        verify(serviceRepository, times(1)).save(s);
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void setServiceEnabled_false_updatesAndPublishesEvent() {
        ShippingService s = svc(5L, "UPS", "GROUND", true);
        when(serviceRepository.findById(5L)).thenReturn(Optional.of(s));
        when(serviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<ShippingService> r = service.setServiceEnabled(5L, false);

        assertEquals("SUCCESS", r.getStatus());
        assertEquals(false, r.getData().isEnabled());
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void setServiceEnabled_notFound_returns404_andPublishesNoEvent() {
        when(serviceRepository.findById(99L)).thenReturn(Optional.empty());

        ApiResponse<ShippingService> r = service.setServiceEnabled(99L, true);

        assertEquals("ERROR", r.getStatus());
        assertEquals(404, r.getCode());
        verify(serviceRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    // ================ upsertRule() ================

    @Test
    void upsertRule_blankShipviaCd_returns422() {
        ApiResponse<ShipViaMapping> r = service.upsertRule(
                null, "", "C001", "COUNTRY", "US", 1L, List.of(), List.of());

        assertEquals("ERROR", r.getStatus());
        assertEquals(422, r.getCode());
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void upsertRule_serviceIdMissing_returns422() {
        ApiResponse<ShipViaMapping> r = service.upsertRule(
                null, "GROUND", "C001", "COUNTRY", "US", null, List.of(), List.of());

        assertEquals("ERROR", r.getStatus());
        assertEquals(422, r.getCode());
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void upsertRule_conflict_returns409() {
        // Duplicate: existing rule with same code + client + dest.
        ShipViaMapping existing = ShipViaMapping.builder()
                .id(5L).shipviaCd("GROUND").clientCode("C001")
                .destType("COUNTRY").destValue("US").serviceId(1L).build();
        when(serviceRepository.findById(1L)).thenReturn(Optional.of(svc(1L, "UPS", "GROUND", true)));
        when(ruleRepository.findByShipviaCdIgnoreCase("GROUND")).thenReturn(List.of(existing));

        ApiResponse<ShipViaMapping> r = service.upsertRule(
                null, "GROUND", "C001", "COUNTRY", "US", 1L, List.of(), List.of());

        assertEquals("ERROR", r.getStatus());
        assertEquals(409, r.getCode());
        verify(ruleRepository, never()).save(any());
    }

    // ================ deleteRule() ================

    @Test
    void deleteRule_found_cascadesChildRowsAndDeletes() {
        ShipViaMapping rule = ShipViaMapping.builder().id(7L).build();
        when(ruleRepository.findById(7L)).thenReturn(Optional.of(rule));

        ApiResponse<Void> r = service.deleteRule(7L);

        assertEquals("SUCCESS", r.getStatus());
        verify(rulePackageRepository, times(1)).deleteAllByRuleId(7L);
        verify(ruleWarehouseRepository, times(1)).deleteAllByRuleId(7L);
        verify(ruleRepository, times(1)).delete(rule);
    }

    @Test
    void deleteRule_notFound_returnsSuccess_noopDelete() {
        when(ruleRepository.findById(99L)).thenReturn(Optional.empty());

        ApiResponse<Void> r = service.deleteRule(99L);

        assertEquals("SUCCESS", r.getStatus());
        verify(rulePackageRepository, never()).deleteAllByRuleId(anyLong());
        verify(ruleRepository, never()).delete(any());
    }

    // ================ setServicePackages() ================

    @Test
    void setServicePackages_serviceNotFound_returns404() {
        when(serviceRepository.findById(99L)).thenReturn(Optional.empty());

        ApiResponse<List<ServicePackage>> r = service.setServicePackages(99L, List.of());

        assertEquals("ERROR", r.getStatus());
        assertEquals(404, r.getCode());
        verify(servicePackageRepository, never()).deleteByServiceId(anyLong());
        verify(servicePackageRepository, never()).save(any());
    }

    @Test
    void setServicePackages_replacesExistingLinks() {
        when(serviceRepository.findById(5L)).thenReturn(Optional.of(svc(5L, "UPS", "GROUND", true)));
        when(presetRepository.existsById(1L)).thenReturn(true);
        when(presetRepository.existsById(2L)).thenReturn(true);
        when(servicePackageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ServicePackage l1 = ServicePackage.builder().presetId(1L).build();
        ServicePackage l2 = ServicePackage.builder().presetId(2L).build();
        ApiResponse<List<ServicePackage>> r = service.setServicePackages(5L, List.of(l1, l2));

        assertEquals("SUCCESS", r.getStatus());
        assertEquals(2, r.getData().size());
        verify(servicePackageRepository, times(1)).deleteByServiceId(5L);
        verify(servicePackageRepository, times(1)).flush();
        verify(servicePackageRepository, times(2)).save(any());
    }

    // ================ listPresets() ================

    @Test
    void listPresets_returnsRepoRows_inDefaultThenNameOrder() {
        when(presetRepository.findAllByOrderByIsDefaultDescNameAsc())
                .thenReturn(List.of(preset(1L, "Default box", true), preset(2L, "Other", false)));

        ApiResponse<List<PackagePreset>> r = service.listPresets();

        assertEquals("SUCCESS", r.getStatus());
        assertEquals(2, r.getData().size());
        verify(presetRepository, times(1)).findAllByOrderByIsDefaultDescNameAsc();
    }

    // ================ syncPackagesFromCarrier() ================

    @Test
    void syncPackagesFromCarrier_unknownCarrier_returns422() {
        ApiResponse<Map<String, Object>> r = service.syncPackagesFromCarrier("BOGUS", "US");

        assertEquals("ERROR", r.getStatus());
        assertEquals(422, r.getCode());
        verify(upsMock, never()).listPackages(any(), any(), any());
        verify(fedexMock, never()).listPackages(any(), any(), any());
    }

    @Test
    void syncPackagesFromCarrier_notLive_refuses() {
        when(carrierAccountRefRepository.findPlatformAccountsByCarrier("UPS"))
                .thenReturn(List.of());
        when(upsMock.listPackages(eq("US"), any(), any())).thenReturn(
                new CarrierConnector.PackageAvailability(List.of(), false, "built-in fallback"));

        ApiResponse<Map<String, Object>> r = service.syncPackagesFromCarrier("UPS", "US");

        assertEquals("ERROR", r.getStatus());
        assertEquals(422, r.getCode());
        assertTrue(r.getMessage().contains("not verified live"));
        verify(presetRepository, never()).save(any());
    }

    @Test
    void syncPackagesFromCarrier_live_addsNewPreset_andPublishesEvent() {
        CarrierAccountRef acct = CarrierAccountRef.builder()
                .id(1L).carrierCode("UPS")
                .clientId("cid").clientSecret("csecret").accountNumber("ACCT-1")
                .environment("SANDBOX").active(true).build();
        when(carrierAccountRefRepository.findPlatformAccountsByCarrier("UPS"))
                .thenReturn(List.of(acct));
        when(upsMock.getAccessToken("cid", "csecret", "ACCT-1")).thenReturn("live-token");
        when(upsMock.listPackages("US", "live-token", "SANDBOX")).thenReturn(
                new CarrierConnector.PackageAvailability(
                        List.of(new CarrierConnector.PackageOffering(
                                "SMALL_BOX", "Small Box",
                                java.math.BigDecimal.valueOf(10), java.math.BigDecimal.valueOf(8),
                                java.math.BigDecimal.valueOf(6), java.math.BigDecimal.valueOf(5),
                                true, "DOMESTIC")),
                        true, "UPS Packaging API"));
        when(presetRepository.findByCarrierIgnoreCaseAndCarrierPackageCodeIgnoreCaseAndOriginCountryIgnoreCase(
                "UPS", "SMALL_BOX", "US")).thenReturn(Optional.empty());
        when(presetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<Map<String, Object>> r = service.syncPackagesFromCarrier("UPS", "US");

        assertEquals("SUCCESS", r.getStatus());
        assertEquals(1, r.getData().get("added"));
        verify(presetRepository, times(1)).save(any());
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    // ================ savePreset() ================

    @Test
    void savePreset_blankName_returns422() {
        PackagePreset req = PackagePreset.builder().name("").kind("CUSTOM").build();

        ApiResponse<PackagePreset> r = service.savePreset(null, req);

        assertEquals("ERROR", r.getStatus());
        assertEquals(422, r.getCode());
        verify(presetRepository, never()).save(any());
    }

    @Test
    void savePreset_customKind_withoutDims_returns422() {
        PackagePreset req = PackagePreset.builder().name("MyBox").kind("CUSTOM").build();

        ApiResponse<PackagePreset> r = service.savePreset(null, req);

        assertEquals("ERROR", r.getStatus());
        assertEquals(422, r.getCode());
        verify(presetRepository, never()).save(any());
    }

    @Test
    void savePreset_carrierKind_withoutCode_returns422() {
        PackagePreset req = PackagePreset.builder().name("BigBox").kind("CARRIER").build();

        ApiResponse<PackagePreset> r = service.savePreset(null, req);

        assertEquals("ERROR", r.getStatus());
        assertEquals(422, r.getCode());
    }

    @Test
    void savePreset_nameClashOnCreate_returns409() {
        PackagePreset req = preset(null, "Small box", false);
        when(presetRepository.findByNameIgnoreCase("Small box"))
                .thenReturn(Optional.of(preset(1L, "Small box", false)));

        ApiResponse<PackagePreset> r = service.savePreset(null, req);

        assertEquals("ERROR", r.getStatus());
        assertEquals(409, r.getCode());
        verify(presetRepository, never()).save(any());
    }

    @Test
    void savePreset_create_tagsSourceCustom_andPersists() {
        PackagePreset req = preset(null, "Widget Box", false);
        when(presetRepository.findByNameIgnoreCase("Widget Box")).thenReturn(Optional.empty());
        // Echo the save so we can inspect the persisted entity.
        when(presetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<PackagePreset> r = service.savePreset(null, req);

        assertEquals("SUCCESS", r.getStatus());
        assertEquals("CUSTOM", r.getData().getSource());
        assertEquals("Widget Box", r.getData().getName());
        verify(presetRepository, times(1)).save(any());
    }

    @Test
    void savePreset_update_findsById_andSavesWithoutRetaggingSource() {
        PackagePreset existing = preset(42L, "Widget Box", false);
        existing.setSource("CARRIER_API");
        PackagePreset req = preset(42L, "Widget Box", false);
        when(presetRepository.findByNameIgnoreCase("Widget Box"))
                .thenReturn(Optional.of(existing));
        when(presetRepository.findById(42L)).thenReturn(Optional.of(existing));
        when(presetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<PackagePreset> r = service.savePreset(42L, req);

        assertEquals("SUCCESS", r.getStatus());
        // Update path does NOT re-tag source: original CARRIER_API preserved.
        assertEquals("CARRIER_API", r.getData().getSource());
        verify(presetRepository, times(1)).findById(42L);
    }

    // ================ setDefaultPreset() ================

    @Test
    void setDefaultPreset_notFound_returns404() {
        when(presetRepository.findById(99L)).thenReturn(Optional.empty());

        ApiResponse<PackagePreset> r = service.setDefaultPreset(99L);

        assertEquals("ERROR", r.getStatus());
        assertEquals(404, r.getCode());
        verify(presetRepository, never()).save(any());
    }

    @Test
    void setDefaultPreset_demotesPriorDefault_thenMarksNew() {
        PackagePreset target = preset(3L, "New default", false);
        PackagePreset priorDefault = preset(1L, "Old default", true);
        when(presetRepository.findById(3L)).thenReturn(Optional.of(target));
        when(presetRepository.findByIsDefaultTrue()).thenReturn(List.of(priorDefault));
        when(presetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<PackagePreset> r = service.setDefaultPreset(3L);

        assertEquals("SUCCESS", r.getStatus());
        assertEquals(Boolean.TRUE, r.getData().getIsDefault());
        // Prior default was written back with isDefault=false, AND the target
        // was written back with isDefault=true.
        verify(presetRepository, times(2)).save(any());
        assertEquals(Boolean.FALSE, priorDefault.getIsDefault());
    }

    // ================ deletePreset() ================

    @Test
    void deletePreset_notFound_returnsSuccess_noopDelete() {
        when(presetRepository.findById(99L)).thenReturn(Optional.empty());

        ApiResponse<Void> r = service.deletePreset(99L);

        // Sprint 51 audit-pattern: idempotent-on-unknown-id (SUCCESS, not 404).
        // Documented behavior — deletion is idempotent for FE convenience.
        assertEquals("SUCCESS", r.getStatus());
        assertEquals(200, r.getCode());
        assertNull(r.getData());
        verify(presetRepository, never()).delete(any());
    }

    @Test
    void deletePreset_defaultRow_returns409() {
        PackagePreset def = preset(3L, "Default box", true);
        when(presetRepository.findById(3L)).thenReturn(Optional.of(def));

        ApiResponse<Void> r = service.deletePreset(3L);

        assertEquals("ERROR", r.getStatus());
        assertEquals(409, r.getCode());
        assertTrue(r.getMessage().contains("default"));
        verify(presetRepository, never()).delete(any());
    }

    @Test
    void deletePreset_nonDefault_deletesAndSuccess() {
        PackagePreset p = preset(3L, "Small box", false);
        when(presetRepository.findById(3L)).thenReturn(Optional.of(p));

        ApiResponse<Void> r = service.deletePreset(3L);

        assertEquals("SUCCESS", r.getStatus());
        verify(presetRepository, times(1)).delete(p);
    }
}
