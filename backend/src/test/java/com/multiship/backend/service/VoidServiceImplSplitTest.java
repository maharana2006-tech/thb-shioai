package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.VoidLabelResponseDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.repository.ShipmentBatchRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.carriers.CarrierConnector.VoidResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Perf P3 phase 1 — split path (phaseSplitEnabled=true). Mirrors
 * VoidServiceImplTest's wiring; adds a TransactionTemplate stub that
 * invokes the callback inline so unit tests don't need a real tx manager.
 */
class VoidServiceImplSplitTest {

    private OrderTrackingRepository trackingRepo;
    private CarrierAccountRefRepository accountRepo;
    private CarrierService carrierService;
    private ShipmentBatchRepository batchRepo;
    private CarrierProperties carrierProperties;
    private CarrierConnector connector;
    private OrderRepository orderRepo;
    private VoidServiceImpl service;

    @BeforeEach
    void setUp() {
        trackingRepo = mock(OrderTrackingRepository.class);
        accountRepo = mock(CarrierAccountRefRepository.class);
        carrierService = mock(CarrierService.class);
        batchRepo = mock(ShipmentBatchRepository.class);
        carrierProperties = new CarrierProperties();
        CarrierProperties.ShipperDefaults shipper = new CarrierProperties.ShipperDefaults();
        shipper.setCountryCode("US");
        shipper.setName("Test");
        shipper.setPhone("0");
        shipper.setAddressLine1("1");
        shipper.setCity("City");
        shipper.setState("ST");
        shipper.setPostalCode("00000");
        try {
            java.lang.reflect.Field f = CarrierProperties.class.getDeclaredField("shipper");
            f.setAccessible(true);
            f.set(carrierProperties, shipper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        connector = mock(CarrierConnector.class);
        orderRepo = mock(OrderRepository.class);

        service = new VoidServiceImpl(trackingRepo, accountRepo, carrierService, batchRepo,
                carrierProperties, orderRepo,
                new TenantScopeEnforcer(new AccessScopePolicy(false)));

        // Enable the split path + inject a transaction template that
        // invokes the callback inline (no real tx manager needed).
        ReflectionTestUtils.setField(service, "phaseSplitEnabled", true);
        TransactionTemplate txTemplate = mock(TransactionTemplate.class);
        when(txTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });
        ReflectionTestUtils.setField(service, "requiresNewTransactionTemplate", txTemplate);
    }

    private OrderTracking row(String status, String tracking, String carrier, String accountNo) {
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

    private void setupHappyPathWiring(OrderTracking row) {
        when(trackingRepo.findByOrderNoForUpdate(1)).thenReturn(Optional.of(row));
        when(carrierService.getCarrierConnector(anyString())).thenReturn(connector);
        when(connector.getCarrierCode()).thenReturn("UPS");
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(anyString(), anyString()))
                .thenReturn(Optional.of(account()));
        when(connector.getAccessToken(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("bearer-token");
    }

    @Test
    void splitPath_happyPath_setsInFlightThenNullsItAndWritesVoided() {
        OrderTracking tracking = row("GENERATED", "1Z999", "UPS", "A1");
        setupHappyPathWiring(tracking);
        when(connector.voidShipment(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new VoidResult("1Z999", true, "VOIDED", "ok", null));

        ApiResponse<VoidLabelResponseDTO> resp = service.voidLabel(1);

        assertEquals(200, resp.getCode());
        assertTrue(resp.getData().isVoided());

        // In_flight_since was set during phase A and nulled during phase C.
        // Last-saved state should be in_flight_since=null, status=VOIDED.
        ArgumentCaptor<OrderTracking> captor = ArgumentCaptor.forClass(OrderTracking.class);
        verify(trackingRepo, atLeast(2)).save(captor.capture());
        OrderTracking lastSave = captor.getValue();
        assertNull(lastSave.getInFlightSince(), "phase C must null in_flight_since");
        assertEquals("VOIDED", lastSave.getStatus());
    }

    @Test
    void splitPath_alreadyInFlight_shortCircuitsWith409() {
        OrderTracking tracking = row("GENERATED", "1Z999", "UPS", "A1");
        tracking.setInFlightSince(Instant.now().minusSeconds(5));
        when(trackingRepo.findByOrderNoForUpdate(1)).thenReturn(Optional.of(tracking));

        ApiResponse<VoidLabelResponseDTO> resp = service.voidLabel(1);

        assertEquals(409, resp.getCode());
        assertNotNull(resp.getMessage());
        verify(connector, never()).voidShipment(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void splitPath_alreadyVoided_shortCircuitsBeforeReservation() {
        OrderTracking tracking = row("VOIDED", "1Z999", "UPS", "A1");
        when(trackingRepo.findByOrderNoForUpdate(1)).thenReturn(Optional.of(tracking));

        ApiResponse<VoidLabelResponseDTO> resp = service.voidLabel(1);

        assertEquals(200, resp.getCode());
        assertEquals("ALREADY_VOIDED", resp.getData().getStatus());
        verify(connector, never()).voidShipment(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void splitPath_carrierException_persistsInFlightNullButReturns502() {
        OrderTracking tracking = row("GENERATED", "1Z999", "UPS", "A1");
        setupHappyPathWiring(tracking);
        when(connector.voidShipment(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("carrier timeout"));

        ApiResponse<VoidLabelResponseDTO> resp = service.voidLabel(1);

        assertEquals(502, resp.getCode());
        // Phase C must still run to clear in_flight_since even on carrier failure.
        ArgumentCaptor<OrderTracking> captor = ArgumentCaptor.forClass(OrderTracking.class);
        verify(trackingRepo, atLeast(2)).save(captor.capture());
        assertNull(captor.getValue().getInFlightSince(),
                "phase C must null in_flight_since even on carrier failure");
    }

    @Test
    void splitPath_carrierRefusesVoid_leavesStatusGeneratedButNullsInFlight() {
        OrderTracking tracking = row("GENERATED", "1Z999", "UPS", "A1");
        setupHappyPathWiring(tracking);
        when(connector.voidShipment(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new VoidResult("1Z999", false, "NOT_SUPPORTED", "carrier won't void", null));

        ApiResponse<VoidLabelResponseDTO> resp = service.voidLabel(1);

        assertEquals(200, resp.getCode());
        assertEquals(false, resp.getData().isVoided());
        ArgumentCaptor<OrderTracking> captor = ArgumentCaptor.forClass(OrderTracking.class);
        verify(trackingRepo, atLeast(2)).save(captor.capture());
        OrderTracking lastSave = captor.getValue();
        assertNull(lastSave.getInFlightSince());
        assertEquals("GENERATED", lastSave.getStatus(),
                "refusal must NOT flip status — label is still live at the carrier");
    }

    private com.multiship.backend.model.ShipmentBatch batch(int seq, String tracking) {
        com.multiship.backend.model.ShipmentBatch b = new com.multiship.backend.model.ShipmentBatch();
        b.setBatchSeq(seq);
        b.setMasterTrackingNumber(tracking);
        return b;
    }
}
