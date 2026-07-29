package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.TrackingResponseDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioral tests for {@link TrackingServiceImpl}. Mocked repositories +
 * connector so the tests hit no network and no database — pure unit tests
 * of the resolution + caching + fallback logic.
 */
class TrackingServiceImplTest {

    private OrderTrackingRepository trackingRepo;
    private CarrierAccountRefRepository accountRepo;
    private CarrierService carrierService;
    private CarrierConnector connector;
    private TrackingServiceImpl service;

    @BeforeEach
    void setUp() {
        trackingRepo = mock(OrderTrackingRepository.class);
        accountRepo = mock(CarrierAccountRefRepository.class);
        carrierService = mock(CarrierService.class);
        connector = mock(CarrierConnector.class);
        service = new TrackingServiceImpl(trackingRepo, accountRepo, carrierService);
    }

    private OrderTracking tracking(int orderNo, String trackingNumber, String shipVia, String accountNumber) {
        OrderTracking t = new OrderTracking();
        t.setOrderNo(orderNo);
        t.setTrackingNumber(trackingNumber);
        t.setShipViaCd(shipVia);
        t.setAccountNumber(accountNumber);
        return t;
    }

    private CarrierAccountRef account(String carrier, String accountNumber, String clientId, String secret) {
        CarrierAccountRef a = new CarrierAccountRef();
        a.setCarrierCode(carrier);
        a.setAccountNumber(accountNumber);
        a.setClientId(clientId);
        a.setClientSecret(secret);
        a.setEnvironment("PRODUCTION");
        return a;
    }

    private CarrierConnector.TrackingResult liveResult(boolean delivered) {
        return new CarrierConnector.TrackingResult(
                "1Z999", delivered ? "Delivered" : "In Transit", "https://track/1Z999",
                "New York, NY US",
                LocalDateTime.of(2024, 1, 16, 14, 0, 0), delivered, "raw-json",
                List.of(new CarrierConnector.TrackingEvent(
                        LocalDateTime.of(2024, 1, 15, 8, 0, 0),
                        "PU", "Picked up", "Louisville, KY US")));
    }

    /* -------------------------- Validation errors -------------------------- */

    @Test
    void missingOrderNoReturns400() {
        ApiResponse<TrackingResponseDTO> res = service.getLiveTracking(null);
        assertEquals(400, res.getCode());
        assertNull(res.getData());
    }

    @Test
    void unknownOrderReturns404() {
        when(trackingRepo.findByOrderNo(1)).thenReturn(Optional.empty());
        ApiResponse<TrackingResponseDTO> res = service.getLiveTracking(1);
        assertEquals(404, res.getCode());
    }

    @Test
    void orderWithoutTrackingNumberReturns404() {
        when(trackingRepo.findByOrderNo(1)).thenReturn(Optional.of(
                tracking(1, null, "P80", "740561111")));
        assertEquals(404, service.getLiveTracking(1).getCode());
    }

    @Test
    void orderWithoutCarrierCodeReturns422() {
        when(trackingRepo.findByOrderNo(1)).thenReturn(Optional.of(
                tracking(1, "1Z", null, "740561111")));
        assertEquals(422, service.getLiveTracking(1).getCode());
    }

    /* -------------------------- Carrier code canonicalization -------------------------- */

    @Test
    void canonicalizeErpAliases() {
        assertEquals("UPS", TrackingServiceImpl.canonicalizeCarrierCode("P80"));
        assertEquals("FEDEX", TrackingServiceImpl.canonicalizeCarrierCode("F77"));
        assertEquals("USPS", TrackingServiceImpl.canonicalizeCarrierCode("L01"));
        // Case + whitespace
        assertEquals("UPS", TrackingServiceImpl.canonicalizeCarrierCode("  p80  "));
    }

    @Test
    void canonicalizePassesThroughAlreadyCanonicalCodes() {
        assertEquals("UPS", TrackingServiceImpl.canonicalizeCarrierCode("UPS"));
        assertEquals("DHL", TrackingServiceImpl.canonicalizeCarrierCode("DHL"));
    }

    @Test
    void canonicalizeReturnsNullForBlank() {
        assertNull(TrackingServiceImpl.canonicalizeCarrierCode(null));
        assertNull(TrackingServiceImpl.canonicalizeCarrierCode(""));
        assertNull(TrackingServiceImpl.canonicalizeCarrierCode("   "));
    }

    /* -------------------------- Live path -------------------------- */

    @Test
    void liveTrackingHits2ArgAndReturnsSourceLive() {
        when(trackingRepo.findByOrderNo(1)).thenReturn(Optional.of(
                tracking(1, "1Z999", "P80", "740561111")));
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase("740561111", "UPS"))
                .thenReturn(Optional.of(account("UPS", "740561111", "cid", "sec")));
        when(connector.getAccessToken(eq("cid"), eq("sec"), eq("740561111"), eq("PRODUCTION")))
                .thenReturn("real-token");
        when(connector.trackShipment("1Z999", "real-token"))
                .thenReturn(liveResult(false));

        ApiResponse<TrackingResponseDTO> res = service.getLiveTracking(1);
        assertEquals(200, res.getCode());
        TrackingResponseDTO dto = res.getData();
        assertEquals("LIVE", dto.getSource());
        assertEquals("UPS", dto.getCarrierCode());
        assertEquals("In Transit", dto.getStatus());
        assertEquals(1, dto.getEvents().size());
        assertEquals("Picked up", dto.getEvents().get(0).getDescription());
    }

    @Test
    void secondCallHitsCacheNotConnector() {
        when(trackingRepo.findByOrderNo(1)).thenReturn(Optional.of(
                tracking(1, "1Z999", "P80", "740561111")));
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(anyString(), anyString()))
                .thenReturn(Optional.of(account("UPS", "740561111", "cid", "sec")));
        when(connector.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("real-token");
        when(connector.trackShipment(eq("1Z999"), eq("real-token")))
                .thenReturn(liveResult(false));

        service.getLiveTracking(1);
        ApiResponse<TrackingResponseDTO> second = service.getLiveTracking(1);
        assertEquals("CACHE", second.getData().getSource());
        // 2-arg trackShipment invoked exactly once — cache short-circuits second call.
        verify(connector, times(1)).trackShipment(eq("1Z999"), anyString());
    }

    /* -------------------------- Fallback paths -------------------------- */

    @Test
    void noAccountFoundReturnsStubViaOneArgConnector() {
        when(trackingRepo.findByOrderNo(1)).thenReturn(Optional.of(
                tracking(1, "1Z999", "P80", null)));
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);
        when(accountRepo.findPlatformAccountsByCarrier("UPS")).thenReturn(List.of());
        when(connector.trackShipment("1Z999")).thenReturn(new CarrierConnector.TrackingResult(
                "1Z999", "UNKNOWN", "https://track/1Z999", null, null, false, null));

        ApiResponse<TrackingResponseDTO> res = service.getLiveTracking(1);
        assertEquals("STUB", res.getData().getSource());
        assertEquals("UNKNOWN", res.getData().getStatus());
        // Confirm we did NOT call the 2-arg version.
        verify(connector, times(0)).trackShipment(anyString(), anyString());
    }

    @Test
    void tokenAcquisitionFailureFallsBackToStub() {
        when(trackingRepo.findByOrderNo(1)).thenReturn(Optional.of(
                tracking(1, "1Z999", "P80", "740561111")));
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase("740561111", "UPS"))
                .thenReturn(Optional.of(account("UPS", "740561111", "cid", "sec")));
        when(connector.getAccessToken(anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("carrier down"));
        when(connector.trackShipment("1Z999")).thenReturn(new CarrierConnector.TrackingResult(
                "1Z999", "UNKNOWN", "https://track/1Z999", null, null, false, null));

        ApiResponse<TrackingResponseDTO> res = service.getLiveTracking(1);
        assertEquals("STUB", res.getData().getSource());
    }

    @Test
    void trackShipmentFailureFallsBackToStub() {
        when(trackingRepo.findByOrderNo(1)).thenReturn(Optional.of(
                tracking(1, "1Z999", "P80", "740561111")));
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase("740561111", "UPS"))
                .thenReturn(Optional.of(account("UPS", "740561111", "cid", "sec")));
        when(connector.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(connector.trackShipment(eq("1Z999"), eq("tok")))
                .thenThrow(new RuntimeException("timeout"));
        when(connector.trackShipment("1Z999")).thenReturn(new CarrierConnector.TrackingResult(
                "1Z999", "UNKNOWN", "https://track/1Z999", null, null, false, null));

        ApiResponse<TrackingResponseDTO> res = service.getLiveTracking(1);
        assertEquals("STUB", res.getData().getSource());
    }

    @Test
    void unknownCarrierReturns422() {
        when(trackingRepo.findByOrderNo(1)).thenReturn(Optional.of(
                tracking(1, "1Z999", "UNKNOWN_CARRIER", "740561111")));
        when(carrierService.getCarrierConnector("UNKNOWN_CARRIER"))
                .thenThrow(new RuntimeException("no connector"));

        assertEquals(422, service.getLiveTracking(1).getCode());
    }

    /* -------------------------- Account fallback matrix -------------------------- */

    @Test
    void fallsBackToPlatformAccountWhenExactMatchMisses() {
        when(trackingRepo.findByOrderNo(1)).thenReturn(Optional.of(
                tracking(1, "1Z999", "P80", "740561111")));
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);
        // Exact (UPS, 740561111) miss; any-carrier miss; platform hit.
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase("740561111", "UPS"))
                .thenReturn(Optional.empty());
        when(accountRepo.findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc("740561111"))
                .thenReturn(Optional.empty());
        when(accountRepo.findPlatformAccountsByCarrier("UPS"))
                .thenReturn(List.of(account("UPS", "PLATFORM", "pcid", "psec")));
        when(connector.getAccessToken(eq("pcid"), eq("psec"), eq("PLATFORM"), eq("PRODUCTION")))
                .thenReturn("platform-token");
        when(connector.trackShipment(eq("1Z999"), eq("platform-token")))
                .thenReturn(liveResult(false));

        ApiResponse<TrackingResponseDTO> res = service.getLiveTracking(1);
        assertEquals("LIVE", res.getData().getSource());
    }

    @Test
    void deliveredResultCachedLongerImpliedByDto() {
        // Sanity check on the DTO delivered flag propagating — the cache TTL
        // itself is internal implementation.
        when(trackingRepo.findByOrderNo(1)).thenReturn(Optional.of(
                tracking(1, "1Z999", "P80", "740561111")));
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase("740561111", "UPS"))
                .thenReturn(Optional.of(account("UPS", "740561111", "cid", "sec")));
        when(connector.getAccessToken(anyString(), anyString(), any(), any())).thenReturn("tok");
        when(connector.trackShipment(eq("1Z999"), eq("tok")))
                .thenReturn(liveResult(true));

        ApiResponse<TrackingResponseDTO> res = service.getLiveTracking(1);
        assertTrue(res.getData().getDelivered());
        assertNotNull(res.getData().getEstimatedDelivery());
    }
}
