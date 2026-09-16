package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.config.CarrierProperties;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.LabelPackage;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.LabelPackageRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.carriers.UspsDirectConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * PR-F2.5 — pure-Mockito tests for {@link UspsMpsPieceDispatcher}.
 *
 * <p>Every collaborator (repositories, connector, properties) is mocked
 * so the assertions pin the dispatcher's own behaviour: guard checks,
 * per-piece slicing, connector invocation shape, tracking persistence
 * shape. Exception paths from downstream layers propagate verbatim so
 * the queue processor records FAILED with the original message.
 */
class UspsMpsPieceDispatcherTest {

    private UspsDirectConnector connector;
    private OrderRepository orderRepository;
    private OrderTrackingRepository orderTrackingRepository;
    private LabelPackageRepository labelPackageRepository;
    private CarrierAccountRefRepository carrierAccountRefRepository;
    private CarrierProperties carrierProperties;
    private UspsMpsPieceDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        connector = mock(UspsDirectConnector.class);
        orderRepository = mock(OrderRepository.class);
        orderTrackingRepository = mock(OrderTrackingRepository.class);
        labelPackageRepository = mock(LabelPackageRepository.class);
        carrierAccountRefRepository = mock(CarrierAccountRefRepository.class);
        carrierProperties = new CarrierProperties();
        carrierProperties.setDefaultEnvironment("SANDBOX");
        // Populate shipper defaults so builder doesn't return nulls in tests.
        carrierProperties.getShipper().setName("Platform Shipper");
        carrierProperties.getShipper().setPhone("5551234567");
        carrierProperties.getShipper().setAddressLine1("1 Warehouse Way");
        carrierProperties.getShipper().setCity("Newark");
        carrierProperties.getShipper().setState("NJ");
        carrierProperties.getShipper().setPostalCode("07102");
        carrierProperties.getShipper().setCountryCode("US");
        dispatcher = new UspsMpsPieceDispatcher(connector, orderRepository,
                orderTrackingRepository, labelPackageRepository,
                carrierAccountRefRepository, carrierProperties);
    }

    // ================================================================
    // Guards
    // ================================================================

    @Test
    void dispatchPiece_nullItem_throwsIae() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatchPiece(null));
    }

    @Test
    void dispatchPiece_nonMpsItem_throwsIae() {
        UspsLabelQueueItem item = itemBuilder().parentOrderNo(null).build();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatchPiece(item));
        assertTrue(ex.getMessage().contains("non-MPS"),
                "Guard message should name the non-MPS misroute");
    }

    @Test
    void dispatchPiece_nullSequence_throwsIae() {
        UspsLabelQueueItem item = itemBuilder().parentOrderNo(42L).sequenceNumber(null).build();
        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatchPiece(item));
    }

    @Test
    void dispatchPiece_zeroSequence_throwsIae() {
        UspsLabelQueueItem item = itemBuilder().parentOrderNo(42L).sequenceNumber(0).build();
        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatchPiece(item));
    }

    @Test
    void dispatchPiece_negativeSequence_throwsIae() {
        UspsLabelQueueItem item = itemBuilder().parentOrderNo(42L).sequenceNumber(-3).build();
        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatchPiece(item));
    }

    // ================================================================
    // Lookup failures
    // ================================================================

    @Test
    void dispatchPiece_parentOrderMissing_throwsIseNamingOrder() {
        when(orderRepository.findByOrderNo(42)).thenReturn(Optional.empty());

        UspsLabelQueueItem item = itemBuilder().parentOrderNo(42L).sequenceNumber(1).build();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> dispatcher.dispatchPiece(item));
        assertTrue(ex.getMessage().contains("42"),
                "Missing-order message must name the parent order number");
    }

    @Test
    void dispatchPiece_tenantAccountMissing_throwsIseNamingTenant() {
        Order order = orderWithPackagesJson(42, 3);
        when(orderRepository.findByOrderNo(42)).thenReturn(Optional.of(order));
        when(carrierAccountRefRepository.findFirstByCustomerNoIgnoreCaseAndClientDefaultTrueAndActiveTrue(anyString()))
                .thenReturn(Optional.empty());
        when(carrierAccountRefRepository.findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(anyString()))
                .thenReturn(List.of());

        UspsLabelQueueItem item = itemBuilder()
                .parentOrderNo(42L).sequenceNumber(1).tenantCode("ACME").build();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> dispatcher.dispatchPiece(item));
        assertTrue(ex.getMessage().contains("ACME"),
                "Missing-tenant-account message must name the tenant");
        assertTrue(ex.getMessage().contains("USPS"),
                "Missing-tenant-account message should name the carrier");
    }

    @Test
    void dispatchPiece_parentHasFewerPackages_throwsIae() {
        Order order = orderWithPackagesJson(42, 3);   // 3 packages
        when(orderRepository.findByOrderNo(42)).thenReturn(Optional.of(order));

        UspsLabelQueueItem item = itemBuilder()
                .parentOrderNo(42L).sequenceNumber(5).tenantCode("ACME").build();  // asking for piece 5
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatchPiece(item));
        assertTrue(ex.getMessage().contains("5"),
                "Bounds-error message must name the requested sequence");
        assertTrue(ex.getMessage().contains("3"),
                "Bounds-error message must name the actual package count");
    }

    // ================================================================
    // Happy path
    // ================================================================

    @Test
    void dispatchPiece_happyPath_callsConnectorAndPersists() throws Exception {
        Order order = orderWithPackagesJson(42, 5);
        when(orderRepository.findByOrderNo(42)).thenReturn(Optional.of(order));

        CarrierAccountRef acct = uspsAccount("ACME", "USPS-1234", "cid", "csec", "PROD");
        when(carrierAccountRefRepository
                .findFirstByCustomerNoIgnoreCaseAndClientDefaultTrueAndActiveTrue("ACME"))
                .thenReturn(Optional.of(acct));

        when(connector.getAccessToken("cid", "csec", "USPS-1234", "PROD"))
                .thenReturn("token-abc");

        CarrierConnector.ShipmentResult result = new CarrierConnector.ShipmentResult(
                "9400111899223197428347",
                "https://tools.usps.com/go/TrackConfirmAction?tLabels=9400111899223197428347",
                null, "base64-pdf", new BigDecimal("7.35"), null, "{}");
        when(connector.createShipment(any(), eq("token-abc"), eq("PROD"))).thenReturn(result);

        when(labelPackageRepository.findByOrderNoAndSequenceNumber(42, 3))
                .thenReturn(Optional.empty());
        when(orderTrackingRepository.findByOrderNo(42)).thenReturn(Optional.empty());

        UspsLabelQueueItem item = itemBuilder()
                .parentOrderNo(42L).sequenceNumber(3).tenantCode("ACME").build();

        String tracking = dispatcher.dispatchPiece(item);

        assertEquals("9400111899223197428347", tracking);

        // Verify connector was called once with a piece-scoped request.
        ArgumentCaptor<ShipmentRequestDTO> reqCap = ArgumentCaptor.forClass(ShipmentRequestDTO.class);
        verify(connector, times(1))
                .createShipment(reqCap.capture(), eq("token-abc"), eq("PROD"));
        ShipmentRequestDTO sent = reqCap.getValue();
        assertEquals("USPS", sent.getCarrierCode());
        assertEquals("USPS-1234", sent.getAccountNumber());
        assertNotNull(sent.getPackages(), "Sent request must carry a packages list");
        assertEquals(1, sent.getPackages().size(),
                "One USPS API call = one physical package");
        assertEquals(3, sent.getPackages().get(0).getSequenceNumber(),
                "Piece must carry the queue row's sequence number");
        assertTrue(sent.getReferenceNumber().contains("42"),
                "Reference should include parent order number");
        assertTrue(sent.getReferenceNumber().contains("3"),
                "Reference should include the piece sequence");
        assertEquals("42", sent.getPoNumber(),
                "PO field should carry parent order number as string");
        assertEquals("ACME", sent.getDepartmentNumber(),
                "DEPT field should carry the tenant/customer code");

        // Verify per-piece LabelPackage persisted.
        ArgumentCaptor<LabelPackage> pkgCap = ArgumentCaptor.forClass(LabelPackage.class);
        verify(labelPackageRepository, times(1)).save(pkgCap.capture());
        LabelPackage row = pkgCap.getValue();
        assertEquals(Integer.valueOf(42), row.getOrderNo());
        assertEquals(Integer.valueOf(3), row.getSequenceNumber());
        assertEquals("9400111899223197428347", row.getTrackingNumber());
        assertNotNull(row.getCreatedAt(), "Fresh insert must stamp createdAt");
        assertNotNull(row.getUpdatedAt(), "Every write must stamp updatedAt");

        // Piece 3 !=1, so master OrderTracking must NOT be touched.
        verify(orderTrackingRepository, never()).save(any());
    }

    @Test
    void dispatchPiece_piece1_alsoStampsMasterOrderTracking() throws Exception {
        Order order = orderWithPackagesJson(99, 4);
        when(orderRepository.findByOrderNo(99)).thenReturn(Optional.of(order));

        CarrierAccountRef acct = uspsAccount("BETA", "USPS-XYZ", "cid", "csec", "PROD");
        when(carrierAccountRefRepository
                .findFirstByCustomerNoIgnoreCaseAndClientDefaultTrueAndActiveTrue("BETA"))
                .thenReturn(Optional.of(acct));

        when(connector.getAccessToken("cid", "csec", "USPS-XYZ", "PROD"))
                .thenReturn("tok");
        CarrierConnector.ShipmentResult result = new CarrierConnector.ShipmentResult(
                "9400999", "https://usps.com/9400999", null, null,
                new BigDecimal("3.10"), null, "{}");
        when(connector.createShipment(any(), eq("tok"), eq("PROD"))).thenReturn(result);
        when(labelPackageRepository.findByOrderNoAndSequenceNumber(99, 1))
                .thenReturn(Optional.empty());
        when(orderTrackingRepository.findByOrderNo(99)).thenReturn(Optional.empty());

        UspsLabelQueueItem item = itemBuilder()
                .parentOrderNo(99L).sequenceNumber(1).tenantCode("BETA").build();

        String tracking = dispatcher.dispatchPiece(item);
        assertEquals("9400999", tracking);

        // Piece 1 => master OrderTracking upserted.
        ArgumentCaptor<OrderTracking> trkCap = ArgumentCaptor.forClass(OrderTracking.class);
        verify(orderTrackingRepository, times(1)).save(trkCap.capture());
        OrderTracking master = trkCap.getValue();
        assertEquals(Integer.valueOf(99), master.getOrderNo());
        assertEquals("9400999", master.getTrackingNumber());
        assertEquals("GENERATED", master.getStatus());
        assertTrue(Boolean.TRUE.equals(master.getIsLabelGenerated()));
    }

    @Test
    void dispatchPiece_fallbackAccountLookup_whenNoClientDefault() throws Exception {
        Order order = orderWithPackagesJson(42, 2);
        when(orderRepository.findByOrderNo(42)).thenReturn(Optional.of(order));
        // Primary lookup returns empty
        when(carrierAccountRefRepository
                .findFirstByCustomerNoIgnoreCaseAndClientDefaultTrueAndActiveTrue("ACME"))
                .thenReturn(Optional.empty());
        // Fallback lookup returns a USPS account
        CarrierAccountRef fallbackAcct = uspsAccount("ACME", "USPS-FB", "cid", "csec", "SANDBOX");
        when(carrierAccountRefRepository
                .findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc("ACME"))
                .thenReturn(List.of(fallbackAcct));

        when(connector.getAccessToken("cid", "csec", "USPS-FB", "SANDBOX"))
                .thenReturn("tok");
        when(connector.createShipment(any(), eq("tok"), eq("SANDBOX")))
                .thenReturn(new CarrierConnector.ShipmentResult(
                        "T-FB", "url", null, null, null, null, "{}"));

        UspsLabelQueueItem item = itemBuilder()
                .parentOrderNo(42L).sequenceNumber(1).tenantCode("ACME").build();

        String tracking = dispatcher.dispatchPiece(item);
        assertEquals("T-FB", tracking);
        verify(connector, times(1)).createShipment(any(), anyString(), anyString());
    }

    @Test
    void dispatchPiece_connectorThrows_propagates() {
        Order order = orderWithPackagesJson(42, 3);
        when(orderRepository.findByOrderNo(42)).thenReturn(Optional.of(order));

        CarrierAccountRef acct = uspsAccount("ACME", "USPS-1", "cid", "csec", "PROD");
        when(carrierAccountRefRepository
                .findFirstByCustomerNoIgnoreCaseAndClientDefaultTrueAndActiveTrue("ACME"))
                .thenReturn(Optional.of(acct));

        when(connector.getAccessToken("cid", "csec", "USPS-1", "PROD")).thenReturn("tok");
        when(connector.createShipment(any(), eq("tok"), eq("PROD")))
                .thenThrow(new IllegalStateException("USPS 401 unauthorised"));

        UspsLabelQueueItem item = itemBuilder()
                .parentOrderNo(42L).sequenceNumber(2).tenantCode("ACME").build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> dispatcher.dispatchPiece(item));
        assertTrue(ex.getMessage().contains("401"),
                "Underlying connector message must survive verbatim");

        // Verify no persistence happened when the connector fails.
        verify(labelPackageRepository, never()).save(any());
        verify(orderTrackingRepository, never()).save(any());
    }

    @Test
    void dispatchPiece_connectorReturnsNoTracking_throwsIse() {
        Order order = orderWithPackagesJson(42, 2);
        when(orderRepository.findByOrderNo(42)).thenReturn(Optional.of(order));

        CarrierAccountRef acct = uspsAccount("ACME", "USPS-1", "cid", "csec", "PROD");
        when(carrierAccountRefRepository
                .findFirstByCustomerNoIgnoreCaseAndClientDefaultTrueAndActiveTrue("ACME"))
                .thenReturn(Optional.of(acct));

        when(connector.getAccessToken("cid", "csec", "USPS-1", "PROD")).thenReturn("tok");
        when(connector.createShipment(any(), eq("tok"), eq("PROD")))
                .thenReturn(new CarrierConnector.ShipmentResult(
                        null, null, null, null, null, null, "{}"));

        UspsLabelQueueItem item = itemBuilder()
                .parentOrderNo(42L).sequenceNumber(1).tenantCode("ACME").build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> dispatcher.dispatchPiece(item));
        assertTrue(ex.getMessage().contains("tracking"),
                "Missing-tracking message should be operator-actionable");
    }

    @Test
    void dispatchPiece_retriedTick_upsertsSameLabelPackageRow() throws Exception {
        Order order = orderWithPackagesJson(42, 3);
        when(orderRepository.findByOrderNo(42)).thenReturn(Optional.of(order));

        CarrierAccountRef acct = uspsAccount("ACME", "USPS-1", "cid", "csec", "PROD");
        when(carrierAccountRefRepository
                .findFirstByCustomerNoIgnoreCaseAndClientDefaultTrueAndActiveTrue("ACME"))
                .thenReturn(Optional.of(acct));

        when(connector.getAccessToken("cid", "csec", "USPS-1", "PROD")).thenReturn("tok");
        when(connector.createShipment(any(), eq("tok"), eq("PROD")))
                .thenReturn(new CarrierConnector.ShipmentResult(
                        "T-RETRY", "url", null, null, null, null, "{}"));

        // Simulate an existing row from a prior FAILED tick.
        LabelPackage existing = LabelPackage.builder()
                .id(500L)
                .orderNo(42)
                .sequenceNumber(2)
                .trackingNumber("OLD-TRK")
                .build();
        when(labelPackageRepository.findByOrderNoAndSequenceNumber(42, 2))
                .thenReturn(Optional.of(existing));

        UspsLabelQueueItem item = itemBuilder()
                .parentOrderNo(42L).sequenceNumber(2).tenantCode("ACME").build();

        dispatcher.dispatchPiece(item);

        // Upsert must preserve the id; only tracking + timestamps update.
        ArgumentCaptor<LabelPackage> cap = ArgumentCaptor.forClass(LabelPackage.class);
        verify(labelPackageRepository, times(1)).save(cap.capture());
        LabelPackage saved = cap.getValue();
        assertEquals(Long.valueOf(500L), saved.getId(),
                "Retry must upsert onto the same row, not insert a duplicate");
        assertEquals("T-RETRY", saved.getTrackingNumber(),
                "Retry must replace old tracking with fresh USPS-assigned value");
    }

    // ================================================================
    // helpers
    // ================================================================

    private static UspsLabelQueueItem.UspsLabelQueueItemBuilder itemBuilder() {
        return UspsLabelQueueItem.builder()
                .id(1L)
                .tenantCode("ACME")
                .shipmentId(-1234567L)
                .priority(100)
                .status(UspsLabelQueueItem.Status.PROCESSING)
                .retryCount(0);
    }

    private static Order orderWithPackagesJson(int orderNo, int packageCount) {
        Order o = new Order();
        o.setOrderNo(orderNo);
        o.setOrderSuffix(0);
        o.setCustNo("ACME");
        o.setTenantId("ACME");
        o.setShipviaCd("USPS_GROUND_ADVANTAGE");
        // Ship-to.
        o.setShipName("Test Recipient");
        o.setShipAddr1("100 Recipient St");
        o.setShiptoCity("Los Angeles");
        o.setShiptoState("CA");
        o.setShiptoZip("90001");
        o.setShiptoCountryCd("US");
        o.setWeight(new BigDecimal("2.5"));
        o.setWeightUnit("LB");
        // packages_json: build a minimal JSON list of the target count.
        StringBuilder sb = new StringBuilder("[");
        for (int i = 1; i <= packageCount; i++) {
            if (i > 1) sb.append(",");
            sb.append("{\"sequenceNumber\":").append(i)
                    .append(",\"weight\":1.5,\"weightUnit\":\"LB\"")
                    .append(",\"length\":10,\"width\":6,\"height\":4,\"dimUnit\":\"IN\"")
                    .append(",\"packageType\":\"YOUR_PACKAGING\"}");
        }
        sb.append("]");
        o.setPackagesJson(sb.toString());
        return o;
    }

    private static CarrierAccountRef uspsAccount(String customerNo, String accountNumber,
                                                  String clientId, String clientSecret, String env) {
        return CarrierAccountRef.builder()
                .id(1L)
                .accountNumber(accountNumber)
                .carrierCode("USPS")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .environment(env)
                .customerNo(customerNo)
                .clientDefault(true)
                .active(true)
                .build();
    }
}
