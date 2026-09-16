package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.OrderImportPreviewDTO;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.service.carriers.usps.queue.UspsDirectRoutingService;
import com.multiship.backend.service.carriers.usps.queue.UspsDirectRoutingService.RoutingDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PR-G2 — asserts {@link OrderImportServiceImpl#processGroup} consults
 * {@link UspsDirectRoutingService} before the sync
 * {@code carrierService.generateManualLabel} call and honors the routing
 * verdict per the shape defined in the audit doc
 * ({@code docs/usps-direct-integration-audit.md} BLOCKERs B-I1 / B-I2 +
 * MAJOR M-I1).
 *
 * <p>Fixtures build minimal rows carrying an {@code generatedOrderNo} so
 * the routing decision fires (net-new rows without a pre-existing order
 * skip routing and fall through to {@code generateManualLabel}, per the
 * doc-comment in the implementation).
 *
 * <p>Coverage matrix:
 * <ul>
 *   <li>Routing returns SINGLE_QUEUED → row transitions to QUEUED_USPS,
 *       {@code generateManualLabel} NOT invoked.</li>
 *   <li>Routing returns MPS_QUEUED → row transitions to QUEUED_USPS,
 *       {@code generateManualLabel} NOT invoked; message reflects the
 *       piece count.</li>
 *   <li>Routing returns REJECTED → row transitions to FAILED with the
 *       routing service's remediation string.</li>
 *   <li>Routing returns empty (SYNC) → {@code generateManualLabel}
 *       invoked as normal (covers STAMPS_COM, FedEx, unwired provider).</li>
 *   <li>Routing service null (unwired) → {@code generateManualLabel}
 *       invoked as normal (matches the pre-G2 shape for unit-test
 *       constructors that don't set the field).</li>
 *   <li>Row with null {@code generatedOrderNo} → routing skipped,
 *       {@code generateManualLabel} invoked (the CarrierServiceImpl-side
 *       routing wired in PR-G1 handles net-new rows after order-mint).</li>
 *   <li>Mixed batch of USPS + FedEx orders → USPS rows queue,
 *       FedEx rows generate sync in the same run.</li>
 * </ul>
 */
class OrderImportServiceImplUspsDirectRoutingTest {

    private CarrierService carrierService;
    private UspsDirectRoutingService routing;
    private OrderImportServiceImpl service;

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        routing = mock(UspsDirectRoutingService.class);
        service = new OrderImportServiceImpl(carrierService);
        ReflectionTestUtils.setField(service, "uspsDirectRoutingService", routing);
    }

    // ================================================================
    // fixtures
    // ================================================================

    /** USPS row with an existing generatedOrderNo so routing fires. */
    private static OrderImportRowDTO uspsRow(int rowNumber, int generatedOrderNo) {
        return OrderImportRowDTO.builder()
                .rowNumber(rowNumber)
                .clientCode("ACME")
                .recipientName("Jane " + rowNumber)
                .recipientPhone("2125550100")
                .addressLine1(rowNumber + " Broadway")
                .city("New York")
                .state("NY")
                .postalCode("10001")
                .countryCode("US")
                .carrierCode("USPS")
                .accountNumber("A12345")
                .weight(new BigDecimal("1.5"))
                .weightUnit("LB")
                .generatedOrderNo(generatedOrderNo)  // triggers routing
                .build();
    }

    /** FedEx row with an existing generatedOrderNo so routing fires (and returns SYNC). */
    private static OrderImportRowDTO fedexRow(int rowNumber, int generatedOrderNo) {
        OrderImportRowDTO row = uspsRow(rowNumber, generatedOrderNo);
        row.setCarrierCode("FEDEX");
        return row;
    }

    /** USPS row without a pre-existing orderNo — routing is skipped for this row. */
    private static OrderImportRowDTO uspsNetNewRow(int rowNumber) {
        OrderImportRowDTO row = uspsRow(rowNumber, 0);
        row.setGeneratedOrderNo(null);
        return row;
    }

    private static ApiResponse<LabelGenerationResponse> okSync(long orderNo, String trackingNumber) {
        return ApiResponse.<LabelGenerationResponse>builder()
                .status("success").code(200).message("ok")
                .data(LabelGenerationResponse.builder()
                        .orderNo(orderNo).trackingNumber(trackingNumber)
                        .status("GENERATED").build())
                .build();
    }

    // ================================================================
    // SINGLE_QUEUED path
    // ================================================================

    @Test
    void singleQueuedRowTransitionsToQueuedUspsAndSkipsCarrierCall() {
        when(routing.decide(eq(9001L), any())).thenReturn(Optional.of(
                new RoutingDecision(RoutingDecision.Status.SINGLE_QUEUED, 42L, null, null)));

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(uspsRow(1, 9001)), "alice");

        assertEquals("success", resp.getStatus());
        OrderImportRowDTO row = resp.getData().getRows().get(0);
        assertEquals("QUEUED_USPS", row.getGeneratedStatus());
        assertNotNull(row.getGeneratedMessage());
        assertTrue(row.getGeneratedMessage().contains("42"),
                "queue item id should appear in the operator-facing message: " + row.getGeneratedMessage());
        assertEquals(9001, row.getGeneratedOrderNo(),
                "orderNo must persist on the row so retry can look up the queue item");
        // The whole point of PR-G2 is to STOP sync fan-out for queued rows.
        verifyNoInteractions(carrierService);
    }

    // ================================================================
    // MPS_QUEUED path
    // ================================================================

    @Test
    void mpsQueuedRowTransitionsToQueuedUspsWithPieceCountMessage() {
        when(routing.decide(eq(9002L), any())).thenReturn(Optional.of(
                new RoutingDecision(RoutingDecision.Status.MPS_QUEUED, null, 5, null)));

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(uspsRow(1, 9002)), "alice");

        OrderImportRowDTO row = resp.getData().getRows().get(0);
        assertEquals("QUEUED_USPS", row.getGeneratedStatus());
        assertTrue(row.getGeneratedMessage().contains("5"),
                "piece count should appear in the MPS message: " + row.getGeneratedMessage());
        verifyNoInteractions(carrierService);
    }

    // ================================================================
    // REJECTED (intl MPS) path
    // ================================================================

    @Test
    void rejectedIntlMpsRowTransitionsToFailedWithReason() {
        String reason = "USPS Direct does not support multi-piece international shipments. "
                + "Split into single-package intl shipments manually, or set USPS_PROVIDER=STAMPS_COM.";
        when(routing.decide(eq(9003L), any())).thenReturn(Optional.of(
                new RoutingDecision(RoutingDecision.Status.REJECTED, null, null, reason)));

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(uspsRow(1, 9003)), "alice");

        OrderImportRowDTO row = resp.getData().getRows().get(0);
        assertEquals("FAILED", row.getGeneratedStatus());
        assertEquals(reason, row.getGeneratedMessage(),
                "REJECTED reason must be surfaced verbatim so operators see the remediation path");
        assertEquals(9003, row.getGeneratedOrderNo());
        verifyNoInteractions(carrierService);
    }

    // ================================================================
    // SYNC (routing returns empty)
    // ================================================================

    @Test
    void syncRoutingDecisionFallsThroughToGenerateManualLabel() {
        // Matches STAMPS_COM provider / non-USPS carrier / broken settings —
        // routing returns Optional.empty(), caller must sync.
        when(routing.decide(eq(9004L), any())).thenReturn(Optional.empty());
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(okSync(9004L, "TN-9004"));

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(uspsRow(1, 9004)), "alice");

        OrderImportRowDTO row = resp.getData().getRows().get(0);
        assertEquals("GENERATED", row.getGeneratedStatus());
        verify(carrierService, times(1)).generateManualLabel(any(), any(), eq(9004));
    }

    @Test
    void fedexRowUnderUspsDirectProviderStillSyncsWhenRoutingReturnsEmpty() {
        // Under USPS_DIRECT, the routing service short-circuits non-USPS
        // carriers to Optional.empty(). FedEx must reach the sync path.
        when(routing.decide(eq(9005L), any())).thenReturn(Optional.empty());
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(okSync(9005L, "TN-9005"));

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(fedexRow(1, 9005)), "alice");

        OrderImportRowDTO row = resp.getData().getRows().get(0);
        assertEquals("GENERATED", row.getGeneratedStatus());
        verify(carrierService, times(1)).generateManualLabel(any(), any(), eq(9005));
    }

    // ================================================================
    // Routing service unwired (backwards compat for pure-Mockito tests)
    // ================================================================

    @Test
    void unwiredRoutingServiceFallsThroughToGenerateManualLabel() {
        // Pure-Mockito tests that construct the service without wiring
        // routing must keep working. This mirrors the pre-G2 shape.
        OrderImportServiceImpl bare = new OrderImportServiceImpl(carrierService);
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(okSync(9006L, "TN-9006"));

        ApiResponse<OrderImportPreviewDTO> resp = bare.commit(
                List.of(uspsRow(1, 9006)), "alice");

        assertEquals("GENERATED", resp.getData().getRows().get(0).getGeneratedStatus());
        verify(carrierService, times(1)).generateManualLabel(any(), any(), any());
    }

    // ================================================================
    // Net-new row (no existingOrderNo) — routing skipped at import site
    // ================================================================

    @Test
    void netNewRowWithoutOrderNoSkipsRoutingAtImportSite() {
        // First-attempt import rows don't have an order minted yet. The
        // routing call is skipped here because there's nothing to look up;
        // generateManualLabel is what mints the order + consults the
        // routing service after (PR-G1 wiring on CarrierServiceImpl).
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(okSync(9007L, "TN-9007"));

        service.commit(List.of(uspsNetNewRow(1)), "alice");

        verify(carrierService, times(1)).generateManualLabel(any(), any(), eq(null));
        // Import-site routing must not be consulted when we have no orderNo.
        verify(routing, never()).decide(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    // ================================================================
    // Mixed batch — 3 USPS_DIRECT-queued + 3 FedEx-sync in one commit
    // ================================================================

    @Test
    void mixedBatchQueuesUspsRowsAndSyncsFedexRows() {
        // USPS rows -> routing returns SINGLE_QUEUED
        when(routing.decide(eq(9101L), any())).thenReturn(Optional.of(
                new RoutingDecision(RoutingDecision.Status.SINGLE_QUEUED, 501L, null, null)));
        when(routing.decide(eq(9102L), any())).thenReturn(Optional.of(
                new RoutingDecision(RoutingDecision.Status.SINGLE_QUEUED, 502L, null, null)));
        when(routing.decide(eq(9103L), any())).thenReturn(Optional.of(
                new RoutingDecision(RoutingDecision.Status.SINGLE_QUEUED, 503L, null, null)));

        // FedEx rows -> routing returns empty (SYNC), carrier answers OK
        when(routing.decide(eq(9201L), any())).thenReturn(Optional.empty());
        when(routing.decide(eq(9202L), any())).thenReturn(Optional.empty());
        when(routing.decide(eq(9203L), any())).thenReturn(Optional.empty());
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenAnswer(inv -> {
                    Integer existingOrderNo = inv.getArgument(2);
                    return okSync(existingOrderNo, "TN-" + existingOrderNo);
                });

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(
                        uspsRow(1, 9101),
                        uspsRow(2, 9102),
                        uspsRow(3, 9103),
                        fedexRow(4, 9201),
                        fedexRow(5, 9202),
                        fedexRow(6, 9203)),
                "alice");

        List<OrderImportRowDTO> rows = resp.getData().getRows();
        // Rows are preserved in input order.
        assertEquals("QUEUED_USPS", rows.get(0).getGeneratedStatus());
        assertEquals("QUEUED_USPS", rows.get(1).getGeneratedStatus());
        assertEquals("QUEUED_USPS", rows.get(2).getGeneratedStatus());
        assertEquals("GENERATED",   rows.get(3).getGeneratedStatus());
        assertEquals("GENERATED",   rows.get(4).getGeneratedStatus());
        assertEquals("GENERATED",   rows.get(5).getGeneratedStatus());

        // Only the 3 FedEx rows reached the sync carrier call — the USPS
        // rows were routed to the queue instead. This is the whole point
        // of PR-G2: stop the 24-thread fan-out from hammering USPS Direct.
        verify(carrierService, times(3)).generateManualLabel(any(), any(), any());
    }
}
