package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.model.Order;
import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.model.UspsLabelQueueItem.Status;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import com.multiship.backend.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PR-G1 — pure-Mockito coverage of {@link UspsDirectRoutingService}. No
 * Spring context, no DB; every collaborator (settings, order repo,
 * queue, splitter, queue repo) is mocked so tests exercise decision
 * logic in isolation.
 *
 * <p>Matrix mirrors the PR-G1 brief:
 * <ul>
 *   <li>USPS_PROVIDER=STAMPS_COM → SYNC</li>
 *   <li>USPS_PROVIDER=PROVISIONING_USPS_DIRECT → SYNC</li>
 *   <li>USPS_PROVIDER=USPS_DIRECT + non-USPS order → SYNC</li>
 *   <li>USPS_PROVIDER=USPS_DIRECT + USPS single-piece → SINGLE_QUEUED</li>
 *   <li>USPS_PROVIDER=USPS_DIRECT + USPS MPS domestic → MPS_QUEUED</li>
 *   <li>USPS_PROVIDER=USPS_DIRECT + USPS MPS intl → REJECTED</li>
 *   <li>Re-entrancy (caller = usps-queue-processor) → SYNC even under
 *       USPS_DIRECT</li>
 *   <li>Order not found → SYNC + WARN</li>
 *   <li>Splitter throws IAE (intl-peer) → REJECTED with splitter message</li>
 *   <li>Enqueue throws IllegalStateException (dup) → looks up existing +
 *       returns queued</li>
 *   <li>Enqueue throws unexpected → propagates</li>
 * </ul>
 */
class UspsDirectRoutingServiceTest {

    private SystemSettingService settings;
    private OrderRepository orderRepo;
    private UspsLabelQueueService queue;
    private UspsMpsSplitterService splitter;
    private UspsLabelQueueRepository queueRepo;
    private UspsDirectRoutingService routing;

    private static final long ORDER_NO = 42L;
    private static final String TENANT = "ACME";

    @BeforeEach
    void setUp() {
        settings = mock(SystemSettingService.class);
        orderRepo = mock(OrderRepository.class);
        queue = mock(UspsLabelQueueService.class);
        splitter = mock(UspsMpsSplitterService.class);
        queueRepo = mock(UspsLabelQueueRepository.class);
        routing = new UspsDirectRoutingService(settings, orderRepo, queue, splitter, queueRepo);
    }

    // ================================================================
    // helpers
    // ================================================================

    private void stubProvider(String value) {
        when(settings.getDecrypted("USPS_PROVIDER")).thenReturn(Optional.ofNullable(value));
    }

    private Order stubOrder(long orderNo, String shipviaCd, String tenantId,
                             Integer packageCount, String shiptoCountry) {
        Order o = new Order();
        o.setOrderNo((int) orderNo);
        o.setShipviaCd(shipviaCd);
        o.setTenantId(tenantId);
        o.setPackageCount(packageCount);
        o.setShiptoCountryCd(shiptoCountry);
        when(orderRepo.findByOrderNo((int) orderNo)).thenReturn(Optional.of(o));
        return o;
    }

    private static UserDetails operator() {
        return User.withUsername("alice").password("").authorities("ROLE_USER").build();
    }

    private static UserDetails queueProcessor() {
        return User.withUsername(UspsLabelQueueWiring.QUEUE_SYSTEM_USER)
                .password("").authorities("ROLE_SYSTEM").build();
    }

    // ================================================================
    // Provider gate
    // ================================================================

    @Test
    void stampsProviderShortCircuitsToSync() {
        stubProvider("STAMPS_COM");
        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isEmpty(), "STAMPS_COM must not consult the queue");
        verifyNoInteractions(orderRepo, queue, splitter, queueRepo);
    }

    @Test
    void provisioningProviderShortCircuitsToSync() {
        stubProvider("PROVISIONING_USPS_DIRECT");
        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isEmpty(),
                "PROVISIONING is transitional — runtime connector is still Stamps, no queue");
        verifyNoInteractions(orderRepo, queue, splitter, queueRepo);
    }

    @Test
    void providerLookupFailureFallsBackToSync() {
        when(settings.getDecrypted("USPS_PROVIDER")).thenThrow(new RuntimeException("db down"));
        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isEmpty(), "Settings outage must not brick label generation");
        verifyNoInteractions(orderRepo, queue, splitter, queueRepo);
    }

    // ================================================================
    // Carrier gate
    // ================================================================

    @Test
    void nonUspsCarrierShortCircuitsToSync() {
        stubProvider("USPS_DIRECT");
        stubOrder(ORDER_NO, "FEDEX_GROUND", TENANT, 1, "US");
        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isEmpty(), "FedEx has no USPS platform cap — sync path");
        verifyNoInteractions(queue, splitter, queueRepo);
    }

    @Test
    void orderNotFoundShortCircuitsToSync() {
        stubProvider("USPS_DIRECT");
        when(orderRepo.findByOrderNo((int) ORDER_NO)).thenReturn(Optional.empty());
        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isEmpty(), "Missing order — the caller will blow up on its own path");
        verifyNoInteractions(queue, splitter, queueRepo);
    }

    // ================================================================
    // Re-entrancy guard
    // ================================================================

    @Test
    void queueProcessorCallerShortCircuitsToSync() {
        // Even under USPS_DIRECT + USPS carrier, the queue-processor
        // synthetic user must NOT re-enqueue itself or we'd fan out
        // infinitely on every tick.
        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, queueProcessor());
        assertTrue(d.isEmpty(), "Re-entrant caller (queue processor) must bypass routing");
        // The re-entrancy guard fires BEFORE any settings/orderRepo call,
        // so no other collaborators should be consulted.
        verifyNoInteractions(settings, orderRepo, queue, splitter, queueRepo);
    }

    @Test
    void nullCallerDoesNotTriggerReentrancyGuard() {
        // Background callers (BulkLabelServiceImpl.processOneOrder) pass
        // caller=null — they must still route through the queue.
        stubProvider("USPS_DIRECT");
        stubOrder(ORDER_NO, "USPS", TENANT, 1, "US");
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class)))
                .thenReturn(new UspsLabelQueueService.EnqueueResult(
                        999L, LocalDateTime.of(2026, 9, 16, 12, 0)));

        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, null);
        assertTrue(d.isPresent(), "null caller must NOT trigger the re-entrancy guard");
        assertEquals(UspsDirectRoutingService.RoutingDecision.Status.SINGLE_QUEUED, d.get().status());
    }

    // ================================================================
    // Single-label enqueue
    // ================================================================

    @Test
    void uspsSinglePackageEnqueuesSingleLabel() {
        stubProvider("USPS_DIRECT");
        stubOrder(ORDER_NO, "USPS", TENANT, 1, "US");
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class)))
                .thenReturn(new UspsLabelQueueService.EnqueueResult(
                        1234L, LocalDateTime.of(2026, 9, 16, 12, 0)));

        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isPresent());
        UspsDirectRoutingService.RoutingDecision decision = d.get();
        assertEquals(UspsDirectRoutingService.RoutingDecision.Status.SINGLE_QUEUED, decision.status());
        assertEquals(1234L, decision.queueItemId());
        assertNull(decision.mpsPieceCount());
        assertNull(decision.reason());
        assertTrue(decision.isEnqueued());
        assertFalse(decision.isRejected());

        // The enqueue call must carry the resolved tenantCode + orderNo.
        ArgumentCaptor<UspsLabelQueueService.EnqueueRequest> captor =
                ArgumentCaptor.forClass(UspsLabelQueueService.EnqueueRequest.class);
        verify(queue).enqueue(captor.capture());
        assertEquals(TENANT, captor.getValue().tenantCode());
        assertEquals(ORDER_NO, captor.getValue().shipmentId());
    }

    @Test
    void uspsSingleNullPackageCountTreatedAsSingle() {
        // packageCount=null → the routing service must NOT trip the MPS
        // branch (would call splitter with a null); should fall through
        // to single-label enqueue.
        stubProvider("USPS_DIRECT");
        stubOrder(ORDER_NO, "USPS", TENANT, null, "US");
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class)))
                .thenReturn(new UspsLabelQueueService.EnqueueResult(
                        5L, LocalDateTime.of(2026, 9, 16, 12, 0)));

        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isPresent());
        assertEquals(UspsDirectRoutingService.RoutingDecision.Status.SINGLE_QUEUED, d.get().status());
        verifyNoInteractions(splitter);
    }

    @Test
    void singleLabelDuplicateReusesExistingRow() {
        stubProvider("USPS_DIRECT");
        stubOrder(ORDER_NO, "USPS", TENANT, 1, "US");
        // Enqueue raises the "already queued" IllegalStateException.
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class)))
                .thenThrow(new IllegalStateException(
                        "Shipment " + ORDER_NO + " is already queued (id=77, status=QUEUED)"));
        // The queue repo returns the existing row so the caller can pin
        // its UI to it.
        UspsLabelQueueItem existing = UspsLabelQueueItem.builder()
                .id(77L).shipmentId(ORDER_NO).status(Status.QUEUED).tenantCode(TENANT).build();
        when(queueRepo.findByShipmentId(ORDER_NO)).thenReturn(Optional.of(existing));

        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isPresent());
        assertEquals(UspsDirectRoutingService.RoutingDecision.Status.SINGLE_QUEUED, d.get().status());
        assertEquals(77L, d.get().queueItemId(), "Existing row id, not a fresh one");
    }

    @Test
    void singleLabelDuplicateWithNoExistingRowFallsToSync() {
        // Defensive branch: IllegalStateException raised but the row
        // isn't findable (shouldn't happen but the code guards against
        // it) — fall through to sync so the label still ships.
        stubProvider("USPS_DIRECT");
        stubOrder(ORDER_NO, "USPS", TENANT, 1, "US");
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class)))
                .thenThrow(new IllegalStateException("duplicate but ghosted"));
        when(queueRepo.findByShipmentId(ORDER_NO)).thenReturn(Optional.empty());

        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isEmpty(), "No live row to reuse — fall through to sync");
    }

    @Test
    void singleLabelUnexpectedFailurePropagates() {
        stubProvider("USPS_DIRECT");
        stubOrder(ORDER_NO, "USPS", TENANT, 1, "US");
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class)))
                .thenThrow(new RuntimeException("queue crashed"));

        // Not an IllegalStateException — this is an unexpected outage
        // that shouldn't be silently swallowed. Callers can decide
        // whether to catch it (BulkLabelServiceImpl already does).
        assertThrows(RuntimeException.class, () -> routing.decide(ORDER_NO, operator()));
    }

    @Test
    void singleLabelNullResultFallsToSync() {
        // Contract: null result from enqueue must not NPE — treat as
        // "queue said no" and fall through.
        stubProvider("USPS_DIRECT");
        stubOrder(ORDER_NO, "USPS", TENANT, 1, "US");
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class))).thenReturn(null);

        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isEmpty());
    }

    // ================================================================
    // MPS branch
    // ================================================================

    @Test
    void uspsMpsDomesticFanOutToSplitter() {
        stubProvider("USPS_DIRECT");
        stubOrder(ORDER_NO, "USPS", TENANT, 3, "US");
        when(splitter.splitAndEnqueueForOrder(eq(ORDER_NO), eq(3), eq(TENANT), isNull(), isNull()))
                .thenReturn(new UspsLabelQueueService.EnqueueMpsResult(
                        ORDER_NO, 3,
                        LocalDateTime.of(2026, 9, 16, 12, 0),
                        LocalDateTime.of(2026, 9, 16, 12, 5)));

        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isPresent());
        UspsDirectRoutingService.RoutingDecision decision = d.get();
        assertEquals(UspsDirectRoutingService.RoutingDecision.Status.MPS_QUEUED, decision.status());
        assertEquals(3, decision.mpsPieceCount());
        assertNull(decision.queueItemId());
        assertNull(decision.reason());
        // Single-label queue path must NOT be called for an MPS order.
        verify(queue, never()).enqueue(any(UspsLabelQueueService.EnqueueRequest.class));
    }

    @Test
    void uspsMpsIntlRejectedBeforeSplitter() {
        // Peer intl-MPS guard fires BEFORE the splitter — operators see
        // the routing-layer verdict, not the splitter's opaque IAE.
        stubProvider("USPS_DIRECT");
        stubOrder(ORDER_NO, "USPS", TENANT, 3, "CA");

        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isPresent());
        UspsDirectRoutingService.RoutingDecision decision = d.get();
        assertEquals(UspsDirectRoutingService.RoutingDecision.Status.REJECTED, decision.status());
        assertNotNull(decision.reason());
        assertTrue(decision.reason().contains("multi-piece international"),
                "Reason should name the intl-MPS constraint; got: " + decision.reason());
        assertTrue(decision.reason().contains("STAMPS_COM"),
                "Reason should name the remediation flip; got: " + decision.reason());
        assertTrue(decision.isRejected());
        assertFalse(decision.isEnqueued());
        // Splitter never invoked — routing layer refused first.
        verifyNoInteractions(splitter);
    }

    @Test
    void uspsMpsSplitterIaeSurfacesAsRejected() {
        // Splitter raises IAE for its own validation (e.g. a race where
        // packageCount changed under us). Surface the splitter's message
        // as REJECTED so operators see the exact remediation from
        // whichever guard layer catches the problem.
        stubProvider("USPS_DIRECT");
        stubOrder(ORDER_NO, "USPS", TENANT, 3, "US");
        when(splitter.splitAndEnqueueForOrder(eq(ORDER_NO), eq(3), eq(TENANT), isNull(), isNull()))
                .thenThrow(new IllegalArgumentException(
                        "USPS Direct does not support multi-piece international shipments. "
                                + "Order 42 has 3 packages with recipient country GB"));

        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isPresent());
        assertEquals(UspsDirectRoutingService.RoutingDecision.Status.REJECTED, d.get().status());
        assertTrue(d.get().reason().contains("Order 42"),
                "Should surface the splitter's own message");
    }

    @Test
    void uspsMpsSplitterDuplicateReusesFirstPiece() {
        // Splitter raises IllegalStateException on the wrapped DIVE
        // (concurrent writer landed the batch first). Routing service
        // looks up the first synthetic piece and returns MPS_QUEUED.
        stubProvider("USPS_DIRECT");
        stubOrder(ORDER_NO, "USPS", TENANT, 3, "US");
        when(splitter.splitAndEnqueueForOrder(eq(ORDER_NO), eq(3), eq(TENANT), isNull(), isNull()))
                .thenThrow(new IllegalStateException("MPS batch conflicts with existing queue row"));
        long firstPieceId = UspsMpsSplitterService.syntheticShipmentIdFor(ORDER_NO, 1);
        UspsLabelQueueItem existing = UspsLabelQueueItem.builder()
                .id(111L).shipmentId(firstPieceId).parentOrderNo(ORDER_NO)
                .sequenceNumber(1).status(Status.QUEUED).tenantCode(TENANT).build();
        when(queueRepo.findByShipmentId(firstPieceId)).thenReturn(Optional.of(existing));

        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isPresent());
        assertEquals(UspsDirectRoutingService.RoutingDecision.Status.MPS_QUEUED, d.get().status());
        assertEquals(3, d.get().mpsPieceCount(),
                "Piece count should be the caller's known packageCount, not the DB lookup");
    }

    @Test
    void uspsMpsSplitterDuplicateWithNoExistingRowFallsToSync() {
        // Defensive branch: dup exception but no existing row visible —
        // sync fallback so the caller can still try.
        stubProvider("USPS_DIRECT");
        stubOrder(ORDER_NO, "USPS", TENANT, 3, "US");
        when(splitter.splitAndEnqueueForOrder(eq(ORDER_NO), eq(3), eq(TENANT), isNull(), isNull()))
                .thenThrow(new IllegalStateException("ghosted duplicate"));
        long firstPieceId = UspsMpsSplitterService.syntheticShipmentIdFor(ORDER_NO, 1);
        when(queueRepo.findByShipmentId(firstPieceId)).thenReturn(Optional.empty());

        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isEmpty());
    }

    @Test
    void uspsMpsSplitterRawDIVEAlsoHandled() {
        // Belt-and-braces: if a future refactor removes the service-side
        // IllegalStateException wrap, catch the raw DIVE here so behavior
        // stays consistent.
        stubProvider("USPS_DIRECT");
        stubOrder(ORDER_NO, "USPS", TENANT, 3, "US");
        when(splitter.splitAndEnqueueForOrder(eq(ORDER_NO), eq(3), eq(TENANT), isNull(), isNull()))
                .thenThrow(new DataIntegrityViolationException("unique_shipment_id"));
        long firstPieceId = UspsMpsSplitterService.syntheticShipmentIdFor(ORDER_NO, 1);
        UspsLabelQueueItem existing = UspsLabelQueueItem.builder()
                .id(222L).shipmentId(firstPieceId).parentOrderNo(ORDER_NO)
                .sequenceNumber(1).status(Status.QUEUED).tenantCode(TENANT).build();
        when(queueRepo.findByShipmentId(firstPieceId)).thenReturn(Optional.of(existing));

        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isPresent());
        assertEquals(UspsDirectRoutingService.RoutingDecision.Status.MPS_QUEUED, d.get().status());
        assertEquals(3, d.get().mpsPieceCount());
    }

    @Test
    void uspsMpsBlankCountryTreatedAsDomestic() {
        // Legacy row with a null shiptoCountryCd should NOT hit the intl
        // guard (blank/null = US domestic default — matches
        // UspsMpsSplitterService's own convention).
        stubProvider("USPS_DIRECT");
        stubOrder(ORDER_NO, "USPS", TENANT, 3, null);
        when(splitter.splitAndEnqueueForOrder(eq(ORDER_NO), eq(3), eq(TENANT), isNull(), isNull()))
                .thenReturn(new UspsLabelQueueService.EnqueueMpsResult(
                        ORDER_NO, 3,
                        LocalDateTime.of(2026, 9, 16, 12, 0),
                        LocalDateTime.of(2026, 9, 16, 12, 5)));

        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isPresent());
        assertEquals(UspsDirectRoutingService.RoutingDecision.Status.MPS_QUEUED, d.get().status());
    }

    @Test
    void uspsMpsSplitterNullResultFallsToSync() {
        // Splitter returned null (contract: should always return a
        // non-null result on success). Fall through so the caller
        // doesn't NPE.
        stubProvider("USPS_DIRECT");
        stubOrder(ORDER_NO, "USPS", TENANT, 3, "US");
        when(splitter.splitAndEnqueueForOrder(eq(ORDER_NO), eq(3), eq(TENANT), isNull(), isNull())).thenReturn(null);

        Optional<UspsDirectRoutingService.RoutingDecision> d = routing.decide(ORDER_NO, operator());
        assertTrue(d.isEmpty(), "Null splitter result → sync fallback");
    }

    // ================================================================
    // Tenant resolution
    // ================================================================

    @Test
    void tenantIdBlankFallsBackToCustNo() {
        stubProvider("USPS_DIRECT");
        Order o = new Order();
        o.setOrderNo((int) ORDER_NO);
        o.setShipviaCd("USPS");
        o.setTenantId(null);
        o.setCustNo("FALLBACK");
        o.setPackageCount(1);
        o.setShiptoCountryCd("US");
        when(orderRepo.findByOrderNo((int) ORDER_NO)).thenReturn(Optional.of(o));
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class)))
                .thenReturn(new UspsLabelQueueService.EnqueueResult(
                        1L, LocalDateTime.of(2026, 9, 16, 12, 0)));

        routing.decide(ORDER_NO, operator());
        ArgumentCaptor<UspsLabelQueueService.EnqueueRequest> captor =
                ArgumentCaptor.forClass(UspsLabelQueueService.EnqueueRequest.class);
        verify(queue).enqueue(captor.capture());
        assertEquals("FALLBACK", captor.getValue().tenantCode());
    }

    // ================================================================
    // PR-G5 D3 — tenantCodeHint precedence
    // ================================================================

    @Test
    void tenantCodeHintWinsOverOrderTenantId() {
        // Even when the loaded Order carries a tenantId (drift from a
        // legacy import), the auth-time scope hint takes precedence so
        // the queue row is scoped to what the operator was actually
        // allowed to see.
        stubProvider("USPS_DIRECT");
        Order o = new Order();
        o.setOrderNo((int) ORDER_NO);
        o.setShipviaCd("USPS");
        o.setTenantId("STALE_TENANT");
        o.setCustNo("STALE_CUST");
        o.setPackageCount(1);
        o.setShiptoCountryCd("US");
        when(orderRepo.findByOrderNo((int) ORDER_NO)).thenReturn(Optional.of(o));
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class)))
                .thenReturn(new UspsLabelQueueService.EnqueueResult(
                        1L, LocalDateTime.of(2026, 9, 16, 12, 0)));

        UspsDirectRoutingService.ProvenanceHint scopedHint =
                UspsDirectRoutingService.ProvenanceHint.importBackgroundScoped(999L, "AUTH_SCOPED_TENANT");
        routing.decide(ORDER_NO, null, scopedHint);

        ArgumentCaptor<UspsLabelQueueService.EnqueueRequest> captor =
                ArgumentCaptor.forClass(UspsLabelQueueService.EnqueueRequest.class);
        verify(queue).enqueue(captor.capture());
        assertEquals("AUTH_SCOPED_TENANT", captor.getValue().tenantCode(),
                "tenantCodeHint must win over the order's tenantId/custNo fallback chain");
    }

    @Test
    void tenantCodeHintNullFallsBackToOrderChain() {
        // Manual + bulk-operator paths pass no hint. Pre-G5 derivation
        // (tenantId → custNo → 'unknown') must still fire so those
        // callers behave unchanged.
        stubProvider("USPS_DIRECT");
        Order o = new Order();
        o.setOrderNo((int) ORDER_NO);
        o.setShipviaCd("USPS");
        o.setTenantId("ORDER_TENANT");
        o.setCustNo("ORDER_CUST");
        o.setPackageCount(1);
        o.setShiptoCountryCd("US");
        when(orderRepo.findByOrderNo((int) ORDER_NO)).thenReturn(Optional.of(o));
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class)))
                .thenReturn(new UspsLabelQueueService.EnqueueResult(
                        1L, LocalDateTime.of(2026, 9, 16, 12, 0)));

        routing.decide(ORDER_NO, null,
                UspsDirectRoutingService.ProvenanceHint.manual());

        ArgumentCaptor<UspsLabelQueueService.EnqueueRequest> captor =
                ArgumentCaptor.forClass(UspsLabelQueueService.EnqueueRequest.class);
        verify(queue).enqueue(captor.capture());
        assertEquals("ORDER_TENANT", captor.getValue().tenantCode());
    }

    @Test
    void tenantCodeHintBlankFallsBackToOrderChain() {
        // Defensive: a whitespace-only hint mustn't blot out the order
        // chain (would produce a nonsensical "  " tenant scope on the
        // queue row).
        stubProvider("USPS_DIRECT");
        Order o = new Order();
        o.setOrderNo((int) ORDER_NO);
        o.setShipviaCd("USPS");
        o.setTenantId(null);
        o.setCustNo("ORDER_CUST");
        o.setPackageCount(1);
        o.setShiptoCountryCd("US");
        when(orderRepo.findByOrderNo((int) ORDER_NO)).thenReturn(Optional.of(o));
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class)))
                .thenReturn(new UspsLabelQueueService.EnqueueResult(
                        1L, LocalDateTime.of(2026, 9, 16, 12, 0)));

        UspsDirectRoutingService.ProvenanceHint blankHint =
                UspsDirectRoutingService.ProvenanceHint.importOperatorScoped(999L, "   ");
        routing.decide(ORDER_NO, null, blankHint);

        ArgumentCaptor<UspsLabelQueueService.EnqueueRequest> captor =
                ArgumentCaptor.forClass(UspsLabelQueueService.EnqueueRequest.class);
        verify(queue).enqueue(captor.capture());
        assertEquals("ORDER_CUST", captor.getValue().tenantCode());
    }
}
