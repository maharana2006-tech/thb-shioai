package com.multiship.backend.service;

import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.VoidLabelResponseDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.repository.ShipmentBatchRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.carriers.CarrierConnector.VoidResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 49 Tier 5 Fix 4 — VoidServiceImpl coverage.
 *
 * <p>Guards the idempotency short-circuit, multi-batch composition,
 * and the mixed / all-fail branches. Pure Mockito so tests stay fast.
 */
class VoidServiceImplTest {

    private OrderTrackingRepository trackingRepo;
    private CarrierAccountRefRepository accountRepo;
    private CarrierService carrierService;
    private ShipmentBatchRepository batchRepo;
    private CarrierProperties carrierProperties;
    private CarrierConnector connector;
    private VoidServiceImpl service;

    @BeforeEach
    void setUp() {
        trackingRepo = mock(OrderTrackingRepository.class);
        accountRepo = mock(CarrierAccountRefRepository.class);
        carrierService = mock(CarrierService.class);
        batchRepo = mock(ShipmentBatchRepository.class);
        carrierProperties = new CarrierProperties();
        // Give ShipperDefaults a country so FedEx-style calls don't NPE.
        CarrierProperties.ShipperDefaults shipper = new CarrierProperties.ShipperDefaults();
        shipper.setCountryCode("US");
        // ShipperDefaults exposes setters via Lombok; setName + others required
        // by @NotBlank at bind time — set benign values.
        shipper.setName("Test");
        shipper.setPhone("0");
        shipper.setAddressLine1("1");
        shipper.setCity("City");
        shipper.setState("ST");
        shipper.setPostalCode("00000");
        java.lang.reflect.Field field;
        try {
            field = CarrierProperties.class.getDeclaredField("shipper");
            field.setAccessible(true);
            field.set(carrierProperties, shipper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        connector = mock(CarrierConnector.class);

        service = new VoidServiceImpl(trackingRepo, accountRepo, carrierService, batchRepo, carrierProperties);
    }

    private OrderTracking trackingRow(String status, String tracking, String carrier, String accountNo) {
        OrderTracking t = new OrderTracking();
        t.setOrderNo(1);
        t.setStatus(status);
        t.setTrackingNumber(tracking);
        t.setShipViaCd(carrier);
        t.setAccountNumber(accountNo);
        return t;
    }

    private CarrierAccountRef account() {
        CarrierAccountRef a = new CarrierAccountRef();
        a.setCarrierCode("UPS");
        a.setAccountNumber("A1");
        a.setClientId("cid");
        a.setClientSecret("csecret");
        a.setEnvironment("SANDBOX");
        return a;
    }

    /* -------- idempotency -------- */

    @Test
    void alreadyVoidedShortCircuitsWithoutHittingCarrier() {
        when(trackingRepo.findByOrderNo(1))
                .thenReturn(Optional.of(trackingRow("VOIDED", "1Z999", "UPS", "A1")));

        ApiResponse<VoidLabelResponseDTO> resp = service.voidLabel(1);

        assertEquals(200, resp.getCode());
        assertEquals("ALREADY_VOIDED", resp.getData().getStatus());
        verify(connector, never()).voidShipment(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /* -------- input guards -------- */

    @Test
    void nullOrderNoReturns400() {
        ApiResponse<VoidLabelResponseDTO> resp = service.voidLabel(null);
        assertEquals(400, resp.getCode());
    }

    @Test
    void unknownOrderReturns404() {
        when(trackingRepo.findByOrderNo(42)).thenReturn(Optional.empty());
        ApiResponse<VoidLabelResponseDTO> resp = service.voidLabel(42);
        assertEquals(404, resp.getCode());
    }

    /* -------- multi-batch composition -------- */

    @Test
    void allBatchesVoidedYieldsOverallSuccess() {
        setupHappyPathWiring();
        // Three batches, all succeed.
        when(batchRepo.findByOrderNoOrderByBatchSeqAsc(1)).thenReturn(List.of(
                batch(1, "1Z001"), batch(2, "1Z002"), batch(3, "1Z003")));
        when(connector.voidShipment(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new VoidResult("1Z", true, "VOIDED", "ok", null));

        ApiResponse<VoidLabelResponseDTO> resp = service.voidLabel(1);

        assertEquals(200, resp.getCode());
        assertTrue(resp.getData().isVoided());
        verify(connector, times(3)).voidShipment(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void middleBatchFailureReportsFailureButAttemptsAllBatches() {
        setupHappyPathWiring();
        when(batchRepo.findByOrderNoOrderByBatchSeqAsc(1)).thenReturn(List.of(
                batch(1, "1Z001"), batch(2, "1Z002"), batch(3, "1Z003")));

        // Batch 2 fails; 1 + 3 succeed.
        when(connector.voidShipment(eq("1Z001"), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new VoidResult("1Z001", true, "VOIDED", "ok", null));
        when(connector.voidShipment(eq("1Z002"), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new VoidResult("1Z002", false, "ERROR", "carrier rejected", null));
        when(connector.voidShipment(eq("1Z003"), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new VoidResult("1Z003", true, "VOIDED", "ok", null));

        ApiResponse<VoidLabelResponseDTO> resp = service.voidLabel(1);

        // NOT overall-voided when any batch failed; must attempt all three.
        assertEquals(200, resp.getCode());
        assertEquals("ERROR", resp.getData().getStatus());
        verify(connector, times(3)).voidShipment(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void legacyPathVoidsOrderLevelTrackingWhenNoBatches() {
        setupHappyPathWiring();
        when(batchRepo.findByOrderNoOrderByBatchSeqAsc(1)).thenReturn(List.of());  // legacy — no batches
        when(connector.voidShipment(eq("1Z999"), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new VoidResult("1Z999", true, "VOIDED", "ok", null));

        ApiResponse<VoidLabelResponseDTO> resp = service.voidLabel(1);

        assertTrue(resp.getData().isVoided());
        verify(connector, times(1)).voidShipment(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /* -------- carrier / credential guards -------- */

    @Test
    void unknownCarrierReturns422() {
        when(trackingRepo.findByOrderNo(1)).thenReturn(Optional.of(
                trackingRow("GENERATED", "1Z999", "BOGUS_CARRIER", "A1")));
        when(carrierService.getCarrierConnector(anyString()))
                .thenThrow(new RuntimeException("unknown carrier"));

        ApiResponse<VoidLabelResponseDTO> resp = service.voidLabel(1);
        assertEquals(422, resp.getCode());
    }

    @Test
    void missingCredentialsReturns422() {
        when(trackingRepo.findByOrderNo(1)).thenReturn(Optional.of(
                trackingRow("GENERATED", "1Z999", "UPS", "A1")));
        when(carrierService.getCarrierConnector(anyString())).thenReturn(connector);
        when(connector.getCarrierCode()).thenReturn("UPS");
        // Account exists but has no secret — cannot authenticate for void.
        CarrierAccountRef acct = account();
        acct.setClientSecret(null);
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(anyString(), anyString()))
                .thenReturn(Optional.of(acct));

        ApiResponse<VoidLabelResponseDTO> resp = service.voidLabel(1);
        assertEquals(422, resp.getCode());
    }

    /* -------- helpers -------- */

    private void setupHappyPathWiring() {
        when(trackingRepo.findByOrderNo(1)).thenReturn(Optional.of(
                trackingRow("GENERATED", "1Z999", "UPS", "A1")));
        when(carrierService.getCarrierConnector(anyString())).thenReturn(connector);
        when(connector.getCarrierCode()).thenReturn("UPS");
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(anyString(), anyString()))
                .thenReturn(Optional.of(account()));
        when(connector.getAccessToken(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("bearer-token");
    }

    private com.multiship.backend.model.ShipmentBatch batch(int seq, String tracking) {
        com.multiship.backend.model.ShipmentBatch b = new com.multiship.backend.model.ShipmentBatch();
        b.setBatchSeq(seq);
        b.setMasterTrackingNumber(tracking);
        return b;
    }
}
