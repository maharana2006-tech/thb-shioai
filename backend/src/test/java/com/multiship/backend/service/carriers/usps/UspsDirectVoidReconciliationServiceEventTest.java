package com.multiship.backend.service.carriers.usps;

import com.multiship.backend.events.AppEventBus;
import com.multiship.backend.events.VoidFailedEvent;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-D follow-up — verify {@link UspsDirectVoidReconciliationService}
 * publishes exactly one {@link VoidFailedEvent} per DENIED row and no
 * events on the APPROVED / PENDING / empty / all-APPROVED paths.
 *
 * <p>Pure Mockito — no Spring context, no real Redis. The
 * {@link AppEventBus} is a mock so we can inspect what the service
 * would have pushed on the wire without actually hitting the (absent)
 * {@code StringRedisTemplate}.
 *
 * <p>Also covers the NO-OP fallback: even when the mock event bus
 * itself throws (mimicking the "Redis outage" branch inside
 * {@link AppEventBus#publish}), the reconciliation call must NOT
 * throw — the WARN log stays as the durable fallback and the toast
 * is a UX upgrade only.
 */
class UspsDirectVoidReconciliationServiceEventTest {

    private OrderTrackingRepository trackingRepo;
    private OrderRepository orderRepo;
    private AppEventBus eventBus;
    private UspsDirectVoidReconciliationService service;
    private Map<String, OrderTracking> trackingStore;
    private Map<Integer, Order> orderStore;

    @BeforeEach
    void setUp() {
        trackingRepo = mock(OrderTrackingRepository.class);
        orderRepo = mock(OrderRepository.class);
        eventBus = mock(AppEventBus.class);
        service = new UspsDirectVoidReconciliationService(trackingRepo, orderRepo, eventBus);
        trackingStore = new HashMap<>();
        orderStore = new HashMap<>();

        when(trackingRepo.findByTrackingNumberIgnoreCase(anyString()))
                .thenAnswer(inv -> {
                    String key = ((String) inv.getArgument(0)).toLowerCase();
                    return Optional.ofNullable(trackingStore.get(key));
                });
        when(trackingRepo.save(any(OrderTracking.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(orderRepo.findByOrderNo(any(Integer.class)))
                .thenAnswer(inv -> Optional.ofNullable(orderStore.get(inv.getArgument(0))));
    }

    private OrderTracking seedTracking(String trackingNumber, int orderNo, String status) {
        OrderTracking t = new OrderTracking();
        t.setTrackingNumber(trackingNumber);
        t.setOrderNo(orderNo);
        t.setStatus(status);
        t.setShipViaCd("USPS");
        trackingStore.put(trackingNumber.toLowerCase(), t);
        return t;
    }

    private void seedOrder(int orderNo, String tenantId, String custNo) {
        Order o = new Order();
        o.setOrderNo(orderNo);
        o.setTenantId(tenantId);
        o.setCustNo(custNo);
        orderStore.put(orderNo, o);
    }

    private static InputStream csv(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    // ================================================================
    // Publication happy path
    // ================================================================

    @Test
    void mixedCsvPublishesExactlyOneEventForTheDeniedRow() {
        // Three-row CSV — APPROVED, DENIED, PENDING. The reconciler
        // should push exactly one event (for the DENIED row); APPROVED
        // and PENDING are silent from the toast POV.
        seedTracking("TRK-APPROVED", 5001, "VOIDED");
        seedTracking("TRK-DENIED", 5002, "VOIDED");
        seedTracking("TRK-PENDING", 5003, "VOIDED");
        seedOrder(5002, "ACME", "ACME-INC");

        String body = "TrackingNumber,RefundStatus,RefundReason\n"
                + "TRK-APPROVED,APPROVED,\n"
                + "TRK-DENIED,DENIED,label scanned in transit\n"
                + "TRK-PENDING,PENDING,\n";

        service.reconcile(csv(body));

        ArgumentCaptor<VoidFailedEvent> captor = ArgumentCaptor.forClass(VoidFailedEvent.class);
        verify(eventBus, times(1)).publish(captor.capture());
        VoidFailedEvent event = captor.getValue();
        assertAll(
                () -> assertEquals(5002L, event.orderNo(),
                        "event orderNo mirrors the DENIED tracking row"),
                () -> assertEquals("TRK-DENIED", event.trackingNumber()),
                () -> assertEquals("ACME", event.tenant(),
                        "tenant resolves via Order.tenantId (with cust_no fallback)"),
                () -> assertEquals("label scanned in transit", event.uspsReason(),
                        "reason column passes through verbatim"),
                () -> assertNotNull(event.reconciledAt(),
                        "reconciledAt stamped at publish time"),
                () -> assertEquals(VoidFailedEvent.EVENT_TYPE, event.eventType()),
                () -> assertEquals(VoidFailedEvent.TOPIC, event.topic()));
    }

    @Test
    void tenantResolvesFromCustNoWhenTenantIdIsBlank() {
        // Falls back to cust_no when tenant_id is null — mirrors the
        // COALESCE(tenant_id, cust_no) convention used everywhere else.
        seedTracking("TRK-999", 6000, "VOIDED");
        seedOrder(6000, null, "SHOP-42");

        String body = "TrackingNumber,RefundStatus\nTRK-999,DENIED\n";
        service.reconcile(csv(body));

        ArgumentCaptor<VoidFailedEvent> captor = ArgumentCaptor.forClass(VoidFailedEvent.class);
        verify(eventBus, times(1)).publish(captor.capture());
        assertEquals("SHOP-42", captor.getValue().tenant());
    }

    @Test
    void tenantIsNullWhenOrderRowIsMissing() {
        // Missing Order row — event still fires with tenant=null.
        // SseController treats null-tenant events as everyone-visible,
        // which is safer than dropping an operator toast on the floor.
        seedTracking("TRK-777", 7000, "VOIDED");
        // no seedOrder(7000) — orderRepo.findByOrderNo returns empty

        String body = "TrackingNumber,RefundStatus\nTRK-777,DENIED\n";
        service.reconcile(csv(body));

        ArgumentCaptor<VoidFailedEvent> captor = ArgumentCaptor.forClass(VoidFailedEvent.class);
        verify(eventBus, times(1)).publish(captor.capture());
        assertEquals(null, captor.getValue().tenant());
        assertEquals(7000L, captor.getValue().orderNo());
    }

    // ================================================================
    // No-publish paths
    // ================================================================

    @Test
    void emptyStreamPublishesNothing() {
        service.reconcile(csv(""));
        verify(eventBus, never()).publish(any());
    }

    @Test
    void nullStreamPublishesNothing() {
        service.reconcile(null);
        verify(eventBus, never()).publish(any());
    }

    @Test
    void allApprovedCsvPublishesNothing() {
        seedTracking("TRK-A1", 8001, "VOIDED");
        seedTracking("TRK-A2", 8002, "VOIDED");

        String body = "TrackingNumber,RefundStatus\n"
                + "TRK-A1,APPROVED\n"
                + "TRK-A2,APPROVED\n";
        service.reconcile(csv(body));

        verify(eventBus, never()).publish(any());
    }

    @Test
    void allPendingCsvPublishesNothing() {
        seedTracking("TRK-P1", 8101, "VOIDED");
        String body = "TrackingNumber,RefundStatus\nTRK-P1,PENDING\n";
        service.reconcile(csv(body));
        verify(eventBus, never()).publish(any());
    }

    @Test
    void deniedRowThatIsNotLocallyVoidedPublishesNothing() {
        // Row exists but local status isn't VOIDED — the reconciler
        // skips the state transition AND the toast (no VOID_FAILED
        // flip = nothing operator-actionable happened here).
        seedTracking("TRK-INFLIGHT", 8201, "GENERATED");
        String body = "TrackingNumber,RefundStatus\nTRK-INFLIGHT,DENIED\n";
        service.reconcile(csv(body));
        verify(eventBus, never()).publish(any());
    }

    @Test
    void unknownTrackingPublishesNothing() {
        String body = "TrackingNumber,RefundStatus\nDOES-NOT-EXIST,DENIED\n";
        service.reconcile(csv(body));
        verify(eventBus, never()).publish(any());
    }

    @Test
    void alreadyReconciledDeniedRowPublishesNothing() {
        // Second-pass idempotency — the terminal RECONCILED_DENIED
        // short-circuit fires BEFORE the toast branch, so no
        // duplicate event on a re-upload of the same CSV.
        OrderTracking t = seedTracking("TRK-SEEN", 8301, "VOID_FAILED");
        t.setVoidReconciliationStatus(UspsDirectVoidReconciliationService.RECONCILED_DENIED);

        String body = "TrackingNumber,RefundStatus\nTRK-SEEN,DENIED\n";
        service.reconcile(csv(body));

        verify(eventBus, never()).publish(any());
    }

    // ================================================================
    // NO-OP resilience — bus absent / bus throws
    // ================================================================

    @Test
    void deniedRowStillReconcilesWhenEventBusIsAbsent() {
        // Legacy single-arg constructor — no event bus wired at all.
        // Mimics the "Redis not configured" prod NO-OP mode. The DENIED
        // flip must still succeed and NOT throw.
        UspsDirectVoidReconciliationService noEventService =
                new UspsDirectVoidReconciliationService(trackingRepo);

        OrderTracking t = seedTracking("TRK-NOOP", 9001, "VOIDED");

        String body = "TrackingNumber,RefundStatus\nTRK-NOOP,DENIED\n";
        assertDoesNotThrow(() -> noEventService.reconcile(csv(body)));
        assertEquals(UspsDirectVoidReconciliationService.STATUS_VOID_FAILED, t.getStatus());
        assertEquals(UspsDirectVoidReconciliationService.RECONCILED_DENIED,
                t.getVoidReconciliationStatus());
    }

    @Test
    void deniedRowStillReconcilesWhenEventBusPublishThrows() {
        // Belt-and-braces — the AppEventBus swallows exceptions
        // internally but the service also catches around publish so a
        // future bug in the bus can't take down the reconciliation
        // pipeline.
        doThrow(new RuntimeException("simulated Redis fail")).when(eventBus).publish(any());

        OrderTracking t = seedTracking("TRK-EXPLODE", 9002, "VOIDED");
        String body = "TrackingNumber,RefundStatus\nTRK-EXPLODE,DENIED\n";

        assertDoesNotThrow(() -> service.reconcile(csv(body)));
        assertEquals(UspsDirectVoidReconciliationService.STATUS_VOID_FAILED, t.getStatus());
    }

    @Test
    void reasonColumnAbsentEmitsNullOnTheEvent() {
        // Older USPS reports (pre-2024) don't have a RefundReason
        // column. The event still fires; uspsReason is null.
        seedTracking("TRK-NOREASON", 9100, "VOIDED");
        seedOrder(9100, "T1", "T1CUST");
        String body = "TrackingNumber,RefundStatus\nTRK-NOREASON,DENIED\n";

        service.reconcile(csv(body));

        ArgumentCaptor<VoidFailedEvent> captor = ArgumentCaptor.forClass(VoidFailedEvent.class);
        verify(eventBus, times(1)).publish(captor.capture());
        assertEquals(null, captor.getValue().uspsReason());
    }
}
