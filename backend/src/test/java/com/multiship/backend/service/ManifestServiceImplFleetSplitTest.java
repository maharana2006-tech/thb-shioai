package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ManifestRequestDTO;
import com.multiship.backend.dto.ManifestResponseDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.ClientShipviaCodeMap;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.ClientShipviaCodeMapRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.carriers.CarrierConnector.CloseOutRequest;
import com.multiship.backend.service.carriers.CarrierConnector.CloseOutResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FDX-G2 — coverage for the fleet-split classification + call fan-out in
 * {@link ManifestServiceImpl#closeOut}. Uses pure Mockito against the
 * connector + the 4 classification-chain repos (SSM merged into
 * ClientShipviaCodeMap in V126, so a single repo now carries both
 * per-client and platform-wide rows).
 *
 * <p>The classifier walks: tracking → OrderTracking.orderNo →
 * Order.shipviaCd + tenant → ClientShipviaCodeMap.findMatches
 * (specificity-ordered: per-client row beats platform-wide null-clientCode
 * row in the ORDER BY) → ShippingService.express.
 */
class ManifestServiceImplFleetSplitTest {

    private CarrierService carrierService;
    private CarrierConnector connector;
    private CarrierAccountRefRepository accountRepo;
    private OrderTrackingRepository trackingRepo;
    private OrderRepository orderRepo;
    private ClientShipviaCodeMapRepository clientShipviaRepo;
    private ShippingServiceRepository serviceRepo;
    private ManifestServiceImpl service;

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        connector = mock(CarrierConnector.class);
        accountRepo = mock(CarrierAccountRefRepository.class);
        trackingRepo = mock(OrderTrackingRepository.class);
        orderRepo = mock(OrderRepository.class);
        clientShipviaRepo = mock(ClientShipviaCodeMapRepository.class);
        serviceRepo = mock(ShippingServiceRepository.class);

        // Every connector call resolves the account + returns a real-ish token.
        CarrierAccountRef account = new CarrierAccountRef();
        account.setAccountNumber("740561111");
        account.setCarrierCode("FEDEX");
        account.setClientId("cid");
        account.setClientSecret("cs");
        account.setEnvironment("SANDBOX");
        account.setClientDefault(true);
        when(accountRepo.findByCustomerNoIgnoreCaseAndClientDefaultTrue(anyString()))
                .thenReturn(List.of(account));
        when(accountRepo.findPlatformAccountsByCarrier(anyString())).thenReturn(List.of(account));
        when(carrierService.getCarrierConnector(anyString())).thenReturn(connector);
        when(connector.getCarrierCode()).thenReturn("FEDEX");
        when(connector.getAccessToken(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("real-oauth-bearer-token");

        service = new ManifestServiceImpl(
                carrierService, accountRepo,
                new TenantScopeEnforcer(new AccessScopePolicy(false)),
                trackingRepo, orderRepo, clientShipviaRepo, serviceRepo);
    }

    // ===== single-fleet case (back-compat) =====

    @Test
    void all_ground_trackings_produce_single_manifest_flat_shape() {
        // Classification: 2 Ground trackings, no Express.
        stubTracking("1Z-A", 100, "P80", "ACME");
        stubTracking("1Z-B", 101, "P80", "ACME");
        stubClientShipvia("ACME", "P80", 10L);       // service id 10 = Ground
        stubShippingService(10L, false);              // is_express = false

        when(connector.closeOutDay(any(CloseOutRequest.class), anyString(), anyString()))
                .thenReturn(new CloseOutResult("FEDEX", "GROUP-1", null, null, 2, "MANIFESTED",
                        "ok", "{}"));

        ApiResponse<ManifestResponseDTO> resp = service.closeOut(
                request("FEDEX", "ACME", List.of("1Z-A", "1Z-B")));

        assertEquals(200, resp.getCode());
        ManifestResponseDTO body = resp.getData();
        assertEquals("GROUP-1", body.getManifestId(),
                "single-fleet case must keep flat shape (back-compat with pre-FDX-G callers)");
        assertEquals("MANIFESTED", body.getStatus());
        assertEquals(2, body.getTrackingCount());
        assertNull(body.getManifests(), "single-fleet case must NOT populate manifests[]");
        assertNull(body.getFailedToClassify());
        verify(connector, times(1)).closeOutDay(any(), anyString(), anyString());
    }

    @Test
    void all_ground_call_sends_express_false() {
        stubTracking("1Z-A", 100, "P80", "ACME");
        stubClientShipvia("ACME", "P80", 10L);
        stubShippingService(10L, false);
        when(connector.closeOutDay(any(CloseOutRequest.class), anyString(), anyString()))
                .thenReturn(new CloseOutResult("FEDEX", "GROUP-1", null, null, 1, "MANIFESTED", "ok", "{}"));

        service.closeOut(request("FEDEX", "ACME", List.of("1Z-A")));

        ArgumentCaptor<CloseOutRequest> captor = ArgumentCaptor.forClass(CloseOutRequest.class);
        verify(connector).closeOutDay(captor.capture(), anyString(), anyString());
        assertEquals(false, captor.getValue().express(),
                "Ground-only batch must send express=false so FedEx body picks FDXG");
    }

    @Test
    void all_express_call_sends_express_true() {
        stubTracking("1Z-A", 100, "F77", "ACME");
        stubClientShipvia("ACME", "F77", 11L);
        stubShippingService(11L, true);
        when(connector.closeOutDay(any(CloseOutRequest.class), anyString(), anyString()))
                .thenReturn(new CloseOutResult("FEDEX", "GROUP-E", null, null, 1, "MANIFESTED", "ok", "{}"));

        service.closeOut(request("FEDEX", "ACME", List.of("1Z-A")));

        ArgumentCaptor<CloseOutRequest> captor = ArgumentCaptor.forClass(CloseOutRequest.class);
        verify(connector).closeOutDay(captor.capture(), anyString(), anyString());
        assertEquals(true, captor.getValue().express(),
                "Express-only batch must send express=true so FedEx body picks FDXE");
    }

    // ===== multi-fleet split =====

    @Test
    void mixed_ground_and_express_produces_two_manifests_in_order() {
        stubTracking("1Z-G1", 100, "P80", "ACME");
        stubTracking("1Z-G2", 101, "P80", "ACME");
        stubTracking("1Z-E1", 200, "F77", "ACME");
        stubClientShipvia("ACME", "P80", 10L);
        stubClientShipvia("ACME", "F77", 11L);
        stubShippingService(10L, false);   // Ground
        stubShippingService(11L, true);     // Express

        when(connector.closeOutDay(any(CloseOutRequest.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    CloseOutRequest req = inv.getArgument(0);
                    return req.express()
                            ? new CloseOutResult("FEDEX", "GROUP-E", null, null,
                                    req.trackingNumbers().size(), "MANIFESTED", "ok-express", "{}")
                            : new CloseOutResult("FEDEX", "GROUP-G", null, null,
                                    req.trackingNumbers().size(), "MANIFESTED", "ok-ground", "{}");
                });

        ApiResponse<ManifestResponseDTO> resp = service.closeOut(
                request("FEDEX", "ACME", List.of("1Z-G1", "1Z-E1", "1Z-G2")));

        assertEquals(200, resp.getCode());
        ManifestResponseDTO body = resp.getData();
        assertNull(body.getManifestId(),
                "multi-fleet response must null out flat manifestId to force callers to read manifests[]");
        assertEquals("MANIFESTED", body.getStatus());
        assertEquals(3, body.getTrackingCount());
        assertNotNull(body.getManifests());
        assertEquals(2, body.getManifests().size());
        assertEquals("GROUND", body.getManifests().get(0).getFleet(),
                "GROUND group must be first (preserves call order)");
        assertEquals("GROUP-G", body.getManifests().get(0).getManifestId());
        assertEquals(List.of("1Z-G1", "1Z-G2"), body.getManifests().get(0).getTrackingNumbers());
        assertEquals("EXPRESS", body.getManifests().get(1).getFleet());
        assertEquals("GROUP-E", body.getManifests().get(1).getManifestId());
        assertEquals(List.of("1Z-E1"), body.getManifests().get(1).getTrackingNumbers());
        verify(connector, times(2)).closeOutDay(any(), anyString(), anyString());
    }

    @Test
    void mixed_partial_failure_returns_partial_status() {
        stubTracking("1Z-G1", 100, "P80", "ACME");
        stubTracking("1Z-E1", 200, "F77", "ACME");
        stubClientShipvia("ACME", "P80", 10L);
        stubClientShipvia("ACME", "F77", 11L);
        stubShippingService(10L, false);
        stubShippingService(11L, true);

        when(connector.closeOutDay(any(CloseOutRequest.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    CloseOutRequest req = inv.getArgument(0);
                    return req.express()
                            ? new CloseOutResult("FEDEX", null, null, null, 1, "ERROR", "carrier down", "{}")
                            : new CloseOutResult("FEDEX", "GROUP-G", null, null, 1, "MANIFESTED", "ok", "{}");
                });

        ManifestResponseDTO body = service.closeOut(
                request("FEDEX", "ACME", List.of("1Z-G1", "1Z-E1"))).getData();

        assertEquals("PARTIAL", body.getStatus(),
                "one manifest ok + one failed must aggregate to PARTIAL");
        assertTrue(body.getMessage().contains("1 of 2"),
                "message should report success ratio; got: " + body.getMessage());
    }

    // ===== failedToClassify =====

    @Test
    void unresolvable_trackings_land_in_failedToClassify_and_are_excluded() {
        stubTracking("1Z-G1", 100, "P80", "ACME");
        stubClientShipvia("ACME", "P80", 10L);
        stubShippingService(10L, false);
        when(trackingRepo.findByTrackingNumberIgnoreCase("MYSTERY-1")).thenReturn(Optional.empty());
        when(trackingRepo.findByTrackingNumberIgnoreCase("MYSTERY-2")).thenReturn(Optional.empty());

        when(connector.closeOutDay(any(CloseOutRequest.class), anyString(), anyString()))
                .thenReturn(new CloseOutResult("FEDEX", "GROUP-G", null, null, 1, "MANIFESTED", "ok", "{}"));

        ManifestResponseDTO body = service.closeOut(request("FEDEX", "ACME",
                List.of("1Z-G1", "MYSTERY-1", "MYSTERY-2"))).getData();

        assertNotNull(body.getFailedToClassify());
        assertEquals(List.of("MYSTERY-1", "MYSTERY-2"), body.getFailedToClassify());
        ArgumentCaptor<CloseOutRequest> captor = ArgumentCaptor.forClass(CloseOutRequest.class);
        verify(connector).closeOutDay(captor.capture(), anyString(), anyString());
        assertEquals(List.of("1Z-G1"), captor.getValue().trackingNumbers(),
                "unresolvable trackings must be excluded from the carrier call");
    }

    @Test
    void all_trackings_unresolvable_returns_error_with_failedToClassify() {
        when(trackingRepo.findByTrackingNumberIgnoreCase(anyString())).thenReturn(Optional.empty());

        ManifestResponseDTO body = service.closeOut(request("FEDEX", "ACME",
                List.of("X", "Y", "Z"))).getData();

        assertEquals("ERROR", body.getStatus());
        assertEquals(0, body.getTrackingCount());
        assertEquals(List.of("X", "Y", "Z"), body.getFailedToClassify());
        assertTrue(body.getMessage().contains("failedToClassify"),
                "message should point the operator at the failed list; got: " + body.getMessage());
        verify(connector, times(0)).closeOutDay(any(), anyString(), anyString());
    }

    // ===== platform-wide fallback (per-client row absent, null-clientCode row present) =====

    @Test
    void platform_wide_row_used_when_per_client_alias_absent() {
        // V126 merge — "global fallback" is now a null-clientCode row sitting
        // in the same table. findMatches's ORDER BY (client-match = +4) still
        // picks a per-client row first, but when only a null-clientCode row
        // exists it returns that one.
        stubTracking("1Z-G1", 100, "F77", "ACME");
        ClientShipviaCodeMap platformWide = ClientShipviaCodeMap.builder()
                .clientCode(null).erpCode("F77").serviceId(999L).build();
        when(clientShipviaRepo.findMatches(eq("ACME"), eq("F77"), any(), any(), any()))
                .thenReturn(List.of(platformWide));
        stubShippingService(999L, false);   // Ground per the seeded FedEx service

        when(connector.closeOutDay(any(CloseOutRequest.class), anyString(), anyString()))
                .thenReturn(new CloseOutResult("FEDEX", "GROUP-G", null, null, 1, "MANIFESTED", "ok", "{}"));

        ManifestResponseDTO body = service.closeOut(
                request("FEDEX", "ACME", List.of("1Z-G1"))).getData();

        assertEquals("MANIFESTED", body.getStatus());
        assertNull(body.getFailedToClassify(),
                "platform-wide row should classify — not fall through to failedToClassify");
    }

    // ===== fixtures =====

    private ManifestRequestDTO request(String carrier, String customer, List<String> trackings) {
        ManifestRequestDTO r = new ManifestRequestDTO();
        r.setCarrierCode(carrier);
        r.setCustomerNo(customer);
        r.setTrackingNumbers(trackings);
        return r;
    }

    private void stubTracking(String trackingNumber, int orderNo, String shipviaCd, String tenantCode) {
        OrderTracking ot = new OrderTracking();
        ot.setOrderNo(orderNo);
        ot.setTrackingNumber(trackingNumber);
        when(trackingRepo.findByTrackingNumberIgnoreCase(trackingNumber)).thenReturn(Optional.of(ot));
        Order o = new Order();
        o.setOrderNo(orderNo);
        o.setShipviaCd(shipviaCd);
        o.setTenantId(tenantCode);
        when(orderRepo.findByOrderNo(orderNo)).thenReturn(Optional.of(o));
    }

    private void stubClientShipvia(String tenant, String shipviaCd, long serviceId) {
        ClientShipviaCodeMap map = ClientShipviaCodeMap.builder()
                .clientCode(tenant).erpCode(shipviaCd).serviceId(serviceId).build();
        when(clientShipviaRepo.findMatches(eq(tenant), eq(shipviaCd), any(), any(), any()))
                .thenReturn(List.of(map));
    }

    private void stubShippingService(long id, boolean express) {
        ShippingService s = ShippingService.builder()
                .id(id).carrier("FEDEX").serviceCode("test").name("test").scope("BOTH")
                .express(express).build();
        when(serviceRepo.findById(id)).thenReturn(Optional.of(s));
    }
}
