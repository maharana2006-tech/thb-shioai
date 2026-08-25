package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ManifestRequestDTO;
import com.multiship.backend.dto.ManifestResponseDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.ClientShipviaCodeMap;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.model.ShipViaMapping;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.ClientShipviaCodeMapRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.repository.ShipViaMappingRepository;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FDX-G2 — coverage for the fleet-split classification + call fan-out in
 * {@link ManifestServiceImpl#closeOut}. Uses pure Mockito against the
 * connector + all 5 classification-chain repos so tests are hermetic.
 *
 * <p>The classifier walks: tracking → OrderTracking.orderNo →
 * Order.shipviaCd + tenant → ClientShipviaCodeMap (per-client) OR
 * ShipViaMapping (global) → ShippingService.express.
 */
class ManifestServiceImplFleetSplitTest {

    private CarrierService carrierService;
    private CarrierConnector connector;
    private CarrierAccountRefRepository accountRepo;
    private OrderTrackingRepository trackingRepo;
    private OrderRepository orderRepo;
    private ClientShipviaCodeMapRepository clientShipviaRepo;
    private ShipViaMappingRepository globalShipviaRepo;
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
        globalShipviaRepo = mock(ShipViaMappingRepository.class);
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
                trackingRepo, orderRepo, clientShipviaRepo, globalShipviaRepo, serviceRepo);
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
        // Confirms the CloseOutRequest body carries express=false for the
        // Ground group so FedExConnector picks carrierCode=FDXG.
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
        // Symmetric — Express-only batch sends express=true so FedEx body
        // picks FDXE (fixes the pre-fix silent-Ground-manifest bug).
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
        // 2 Ground + 1 Express trackings → 2 closeOutDay calls, 2 manifests
        // in response. Order preserved: GROUND first, EXPRESS second.
        stubTracking("1Z-G1", 100, "P80", "ACME");
        stubTracking("1Z-G2", 101, "P80", "ACME");
        stubTracking("1Z-E1", 200, "F77", "ACME");
        stubClientShipvia("ACME", "P80", 10L);
        stubClientShipvia("ACME", "F77", 11L);
        stubShippingService(10L, false);   // Ground
        stubShippingService(11L, true);     // Express

        // First call = ground group, second call = express group. Return
        // distinct manifest IDs so we can verify the response wiring.
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
        // Top-level flat fields aggregate; manifestId null so callers must
        // read manifests[].
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
        // 2 connector calls — one per group.
        verify(connector, times(2)).closeOutDay(any(), anyString(), anyString());
    }

    @Test
    void mixed_partial_failure_returns_partial_status() {
        // 1 Ground succeeds, 1 Express fails → status=PARTIAL.
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
        // 1 classifiable Ground + 2 unresolvable (missing OrderTracking rows).
        // The 2 must NOT be sent to the carrier — they land in the
        // failedToClassify list per the locked design decision.
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
        // The 1 classified tracking went in; the 2 unresolved did NOT.
        ArgumentCaptor<CloseOutRequest> captor = ArgumentCaptor.forClass(CloseOutRequest.class);
        verify(connector).closeOutDay(captor.capture(), anyString(), anyString());
        assertEquals(List.of("1Z-G1"), captor.getValue().trackingNumbers(),
                "unresolvable trackings must be excluded from the carrier call");
    }

    @Test
    void all_trackings_unresolvable_returns_error_with_failedToClassify() {
        // Nothing classifies → skip the carrier call entirely + surface
        // an ERROR-shaped response listing every tracking.
        when(trackingRepo.findByTrackingNumberIgnoreCase(anyString())).thenReturn(Optional.empty());

        ManifestResponseDTO body = service.closeOut(request("FEDEX", "ACME",
                List.of("X", "Y", "Z"))).getData();

        assertEquals("ERROR", body.getStatus());
        assertEquals(0, body.getTrackingCount());
        assertEquals(List.of("X", "Y", "Z"), body.getFailedToClassify());
        assertTrue(body.getMessage().contains("failedToClassify"),
                "message should point the operator at the failed list; got: " + body.getMessage());
        // NO connector call — we short-circuited because everything failed.
        verify(connector, times(0)).closeOutDay(any(), anyString(), anyString());
    }

    // ===== global fallback (per-client alias miss) =====

    @Test
    void global_shipviaMapping_used_when_per_client_alias_absent() {
        // Client has no ClientShipviaCodeMap row for "F77" — fall back to
        // the global ShipViaMapping seeded by ShippingConfigSeeder (F77 →
        // FEDEX_GROUND per the standard seed).
        stubTracking("1Z-G1", 100, "F77", "ACME");
        when(clientShipviaRepo.findByClientCodeIgnoreCaseAndErpCodeIgnoreCase("ACME", "F77"))
                .thenReturn(Optional.empty());
        ShipViaMapping global = ShipViaMapping.builder().shipviaCd("F77").serviceId(999L).build();
        when(globalShipviaRepo.findByShipviaCdIgnoreCase("F77")).thenReturn(List.of(global));
        stubShippingService(999L, false);   // Ground per the seeded FedEx service

        when(connector.closeOutDay(any(CloseOutRequest.class), anyString(), anyString()))
                .thenReturn(new CloseOutResult("FEDEX", "GROUP-G", null, null, 1, "MANIFESTED", "ok", "{}"));

        ManifestResponseDTO body = service.closeOut(
                request("FEDEX", "ACME", List.of("1Z-G1"))).getData();

        assertEquals("MANIFESTED", body.getStatus());
        assertNull(body.getFailedToClassify(),
                "global fallback should classify — not fall through to failedToClassify");
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
        when(clientShipviaRepo.findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(tenant, shipviaCd))
                .thenReturn(Optional.of(map));
    }

    private void stubShippingService(long id, boolean express) {
        ShippingService s = ShippingService.builder()
                .id(id).carrier("FEDEX").serviceCode("test").name("test").scope("BOTH")
                .express(express).build();
        when(serviceRepo.findById(id)).thenReturn(Optional.of(s));
    }
}
