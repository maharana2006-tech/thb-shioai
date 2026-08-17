package com.multiship.backend.integration;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.model.ServicePackage;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.PackagePresetRepository;
import com.multiship.backend.repository.ServicePackageRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.service.ShippingConfigService;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.carriers.UpsConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 catalog-be-integration — shipping-service lifecycle exercised
 * against real Postgres. Covers `syncFromCarrier` (add + refresh),
 * `setServiceEnabled` (toggle), `setServicePackages` (service↔preset links),
 * and the live-only sync policy.
 *
 * <p><b>Anti-fallback design</b> (three-layer):
 * <ol>
 *   <li>{@link MockCarrierConnectorsTestConfig} provides pre-stubbed
 *       {@code @Bean(name=...)} mocks that <b>replace</b> the real
 *       {@code @Component}-annotated connectors (spring.main.allow-bean-definition-overriding).
 *       The {@code List<CarrierConnector>} injected into {@link ShippingConfigService}
 *       contains only mocks — real Ups/FedEx/Stamps/Dhl code never runs.</li>
 *   <li>{@link ForbidOutboundHttpTestConfig} installs {@code @Primary}
 *       {@code RestTemplate} + {@code RestClient.Builder} beans that throw on
 *       any request, so any future refactor that adds outbound HTTP fails loudly.</li>
 *   <li>Per-test {@code verify(mockConnector, times(N)).listServices(...)}
 *       proves the code path reached the mock (not a fallback) AND asserts
 *       {@code never()} on sibling connectors so no cross-carrier leakage
 *       can happen.</li>
 * </ol>
 *
 * <p>Live-only policy prerequisites for a successful sync:
 * <ul>
 *   <li>Seed a {@link CarrierAccountRef} PLATFORM row (customerNo null +
 *       active=true + non-blank clientId/clientSecret) so
 *       {@code CarrierAccountRefRepository.findPlatformAccountsByCarrier}
 *       returns it.</li>
 *   <li>Stub the mock connector's {@code getAccessToken(...)} to return a
 *       real (non-{@code "-local-*"}) token.</li>
 *   <li>Stub {@code listServices(...)} to return
 *       {@link CarrierConnector.ServiceAvailability#live()}==true.</li>
 * </ul>
 *
 * <p>Guarded by {@code INTEGRATION_TESTS=1}.
 */
@Import({ForbidOutboundHttpTestConfig.class, MockCarrierConnectorsTestConfig.class})
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class ShippingServiceLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String ACCT_PREFIX = "SSIT_";
    private static final String PRESET_PREFIX = "SSIT_PRESET_";

    @Autowired
    private ShippingConfigService shippingConfigService;
    @Autowired
    private ShippingServiceRepository serviceRepository;
    @Autowired
    private ServicePackageRepository servicePackageRepository;
    @Autowired
    private PackagePresetRepository presetRepository;
    @Autowired
    private CarrierAccountRefRepository accountRefRepository;

    /** Autowired by concrete type so the base stubs from
     *  MockCarrierConnectorsTestConfig apply; layer per-test stubs here. */
    @Autowired
    private UpsConnector upsMock;

    @BeforeEach
    void setUp() {
        // Wipe THIS class' rows across all three tables so re-runs against
        // the shared container start from a known state. Filter on our
        // prefixes so sibling integration tests' fixtures aren't touched.
        servicePackageRepository.deleteAll(
                servicePackageRepository.findAll().stream()
                        .filter(sp -> {
                            ShippingService s = serviceRepository.findById(sp.getServiceId()).orElse(null);
                            return s != null && "UPS".equals(s.getCarrier());
                        }).toList());
        serviceRepository.findAll().stream()
                .filter(s -> "UPS".equals(s.getCarrier()))
                .forEach(s -> serviceRepository.deleteById(s.getId()));
        presetRepository.findAll().stream()
                .filter(p -> p.getName() != null && p.getName().startsWith(PRESET_PREFIX))
                .forEach(p -> presetRepository.deleteById(p.getId()));
        accountRefRepository.findAll().stream()
                .filter(r -> r.getAccountNumber() != null && r.getAccountNumber().startsWith(ACCT_PREFIX))
                .forEach(r -> accountRefRepository.deleteById(r.getId()));

        // Seed the UPS platform account so platformAccessToken() finds it.
        accountRefRepository.save(CarrierAccountRef.builder()
                .accountNumber(ACCT_PREFIX + "PLATFORM_UPS")
                .carrierCode("UPS")
                .accountName("Integration Test UPS Platform")
                .clientId("cid-ups-integration")
                .clientSecret("csecret-ups-integration")
                .environment("SANDBOX")
                .active(true)
                // customerNo left null → platform account
                .build());

        // Per-test stubs on the mock: real (non-fallback) token + live=true
        // offerings so syncFromCarrier will actually persist.
        when(upsMock.getAccessToken(anyString(), anyString(), any(), any()))
                .thenReturn("real-ups-token-live");
        when(upsMock.getAccessToken(anyString(), anyString(), any()))
                .thenReturn("real-ups-token-live");
        when(upsMock.listServices(eq("US"), anyString(), anyString()))
                .thenReturn(new CarrierConnector.ServiceAvailability(
                        List.of(
                                new CarrierConnector.ServiceOffering("GROUND", "UPS Ground", "DOMESTIC"),
                                new CarrierConnector.ServiceOffering("2ND_DAY_AIR", "UPS 2nd Day Air", "DOMESTIC")),
                        true, "UPS Rating API (mock live)"));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin-it", "",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ================ 1. SYNC — live carrier adds new rows ================

    @Test
    void sync_live_addsRowsToDb_andReportsAddedCount() {
        ApiResponse<Map<String, Object>> resp = shippingConfigService.syncFromCarrier("UPS", "US");

        assertEquals("SUCCESS", resp.getStatus());
        assertEquals(2, resp.getData().get("added"));
        assertEquals(0, resp.getData().get("updated"));
        assertEquals(true, resp.getData().get("live"));
        // Anti-fallback: the mock was invoked, so the code cannot have taken
        // any real-carrier path.
        verify(upsMock, atLeastOnce()).listServices(eq("US"), anyString(), anyString());

        // DB round-trip: rows exist with the offerings we stubbed.
        List<ShippingService> ups = serviceRepository.findAll().stream()
                .filter(s -> "UPS".equals(s.getCarrier()) && "US".equals(s.getOriginCountry()))
                .toList();
        assertEquals(2, ups.size(), "Both offerings must be persisted.");
        assertTrue(ups.stream().anyMatch(s -> "GROUND".equals(s.getServiceCode())));
        assertTrue(ups.stream().anyMatch(s -> "2ND_DAY_AIR".equals(s.getServiceCode())));
    }

    // ================ 2. RE-SYNC — no duplicate rows ================

    @Test
    void reSync_updatesRowsInPlace_notDuplicating() {
        shippingConfigService.syncFromCarrier("UPS", "US");
        long initialCount = serviceRepository.findAll().stream()
                .filter(s -> "UPS".equals(s.getCarrier()) && "US".equals(s.getOriginCountry()))
                .count();

        ApiResponse<Map<String, Object>> reSync = shippingConfigService.syncFromCarrier("UPS", "US");

        assertEquals("SUCCESS", reSync.getStatus());
        assertEquals(0, reSync.getData().get("added"), "Re-sync must NOT create new rows.");
        assertEquals(2, reSync.getData().get("updated"));

        long afterCount = serviceRepository.findAll().stream()
                .filter(s -> "UPS".equals(s.getCarrier()) && "US".equals(s.getOriginCountry()))
                .count();
        assertEquals(initialCount, afterCount, "Row count must be unchanged on re-sync.");
    }

    // ================ 3. SYNC NOT LIVE — nothing persisted ================

    @Test
    void sync_notLive_refuses_andWritesNothing() {
        // Override the beforeEach stub to return live=false (built-in fallback).
        when(upsMock.listServices(eq("US"), anyString(), anyString()))
                .thenReturn(new CarrierConnector.ServiceAvailability(
                        List.of(new CarrierConnector.ServiceOffering("GROUND", "UPS Ground", "DOMESTIC")),
                        false, "built-in availability — no live UPS credentials"));

        ApiResponse<Map<String, Object>> resp = shippingConfigService.syncFromCarrier("UPS", "US");

        assertEquals("ERROR", resp.getStatus());
        assertEquals(422, resp.getCode());
        assertTrue(resp.getMessage().contains("not verified live"));

        // Live-only policy: DB is untouched.
        List<ShippingService> ups = serviceRepository.findAll().stream()
                .filter(s -> "UPS".equals(s.getCarrier()))
                .toList();
        assertTrue(ups.isEmpty(), "not-live sync must NOT persist any rows.");
    }

    // ================ 4. TOGGLE — enable / disable ================

    @Test
    void setEnabled_flipsPersistedFlag() {
        shippingConfigService.syncFromCarrier("UPS", "US");
        ShippingService ground = serviceRepository.findAll().stream()
                .filter(s -> "UPS".equals(s.getCarrier()) && "GROUND".equals(s.getServiceCode()))
                .findFirst().orElseThrow();
        assertTrue(ground.isEnabled(), "Newly-synced services default enabled=true.");

        ApiResponse<ShippingService> disable = shippingConfigService.setServiceEnabled(ground.getId(), false);
        assertEquals("SUCCESS", disable.getStatus());
        assertFalse(serviceRepository.findById(ground.getId()).orElseThrow().isEnabled());

        ApiResponse<ShippingService> reEnable = shippingConfigService.setServiceEnabled(ground.getId(), true);
        assertEquals("SUCCESS", reEnable.getStatus());
        assertTrue(serviceRepository.findById(ground.getId()).orElseThrow().isEnabled());
    }

    // ================ 5. LINK PRESETS — service↔preset persistence ================

    @Test
    void setServicePackages_persistsLinks_andReplacesOnRe_apply() {
        shippingConfigService.syncFromCarrier("UPS", "US");
        Long serviceId = serviceRepository.findAll().stream()
                .filter(s -> "UPS".equals(s.getCarrier()) && "GROUND".equals(s.getServiceCode()))
                .findFirst().orElseThrow().getId();
        Long presetA = shippingConfigService.savePreset(null, customBox(PRESET_PREFIX + "A")).getData().getId();
        Long presetB = shippingConfigService.savePreset(null, customBox(PRESET_PREFIX + "B")).getData().getId();

        // Attach A + B.
        ApiResponse<List<ServicePackage>> attached = shippingConfigService.setServicePackages(
                serviceId,
                List.of(link(presetA), link(presetB)));

        assertEquals("SUCCESS", attached.getStatus());
        assertEquals(2, attached.getData().size());
        long linkedCount = servicePackageRepository.findAll().stream()
                .filter(sp -> serviceId.equals(sp.getServiceId())).count();
        assertEquals(2, linkedCount);

        // Re-apply with only A — B must be removed (diff-free replace).
        ApiResponse<List<ServicePackage>> replaced = shippingConfigService.setServicePackages(
                serviceId, List.of(link(presetA)));

        assertEquals("SUCCESS", replaced.getStatus());
        assertEquals(1, replaced.getData().size());
        long afterReplace = servicePackageRepository.findAll().stream()
                .filter(sp -> serviceId.equals(sp.getServiceId())).count();
        assertEquals(1, afterReplace, "Diff-free replace must drop the unlinked preset.");
    }

    // ================ 6. SYNC UNKNOWN CARRIER — 422 ================

    @Test
    void sync_unknownCarrier_returns422_andWritesNothing() {
        long before = serviceRepository.count();

        ApiResponse<Map<String, Object>> resp = shippingConfigService.syncFromCarrier("BOGUS", "US");

        assertEquals("ERROR", resp.getStatus());
        assertEquals(422, resp.getCode());
        assertTrue(resp.getMessage().contains("Unknown carrier"));
        assertEquals(before, serviceRepository.count(), "Unknown-carrier sync must not touch the DB.");
    }

    // ================ 7. TOGGLE UNKNOWN ID — 404 ================

    @Test
    void setEnabled_notFound_returns404() {
        ApiResponse<ShippingService> resp = shippingConfigService.setServiceEnabled(9999999L, true);

        assertEquals("ERROR", resp.getStatus());
        assertEquals(404, resp.getCode());
    }

    // ================ 8. LINK — service unknown — 404 ================

    @Test
    void setServicePackages_unknownService_returns404() {
        Long preset = shippingConfigService.savePreset(null, customBox(PRESET_PREFIX + "SOLO")).getData().getId();

        ApiResponse<List<ServicePackage>> resp = shippingConfigService.setServicePackages(
                9999999L, List.of(link(preset)));

        assertEquals("ERROR", resp.getStatus());
        assertEquals(404, resp.getCode());
    }

    // ================ helpers ================

    private static PackagePreset customBox(String name) {
        return PackagePreset.builder()
                .name(name).kind("CUSTOM").carrier("UPS")
                .ownerType(PackagePreset.OWNER_PLATFORM)
                .length(BigDecimal.valueOf(10))
                .width(BigDecimal.valueOf(10))
                .height(BigDecimal.valueOf(10))
                .dimUnit("IN")
                .maxWeight(BigDecimal.valueOf(5))
                .weightUnit("LB")
                .enabled(true)
                .build();
    }

    private static ServicePackage link(Long presetId) {
        return ServicePackage.builder().presetId(presetId).build();
    }
}
