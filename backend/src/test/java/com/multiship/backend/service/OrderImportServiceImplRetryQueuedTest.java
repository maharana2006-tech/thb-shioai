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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PR-G2 — retry idempotency for rows already parked in QUEUED_USPS.
 *
 * <p>Symptom this fixes (M-I1 in
 * {@code docs/usps-direct-integration-audit.md}): before this guard, a
 * Retry-only-failed pass over a batch with live queue rows would re-enter
 * {@link OrderImportServiceImpl#processGroup} for those queued rows,
 * call {@link UspsDirectRoutingService#decide}, hit
 * {@code IllegalStateException} on the UNIQUE(shipment_id) constraint,
 * and either double-persist the row as FAILED or reuse the live queue
 * item id (both wrong for operator sanity).
 *
 * <p>Coverage matrix:
 * <ul>
 *   <li>Row already in QUEUED_USPS → {@code processGroup} skips it: no
 *       {@code routing.decide} call, no {@code generateManualLabel} call.
 *       Row state stays QUEUED_USPS (queue processor bridge in G3b is
 *       what will flip it).</li>
 *   <li>Row was FAILED on the previous attempt, operator retries → routing
 *       IS consulted, and if it returns SINGLE_QUEUED the row transitions
 *       to QUEUED_USPS. Recovers a failed-retry-exhausted row cleanly.</li>
 *   <li>Full batch of QUEUED_USPS rows → nothing gets re-enqueued (proves
 *       idempotency at the batch level, not just single-row level).</li>
 * </ul>
 */
class OrderImportServiceImplRetryQueuedTest {

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
                .generatedOrderNo(generatedOrderNo)
                .build();
    }

    private static OrderImportRowDTO queuedUspsRow(int rowNumber, int generatedOrderNo) {
        OrderImportRowDTO row = uspsRow(rowNumber, generatedOrderNo);
        row.setGeneratedStatus("QUEUED_USPS");
        row.setGeneratedMessage("Queued USPS Direct label (item 500) — waiting for queue.");
        return row;
    }

    private static OrderImportRowDTO failedRow(int rowNumber, int generatedOrderNo, String msg) {
        OrderImportRowDTO row = uspsRow(rowNumber, generatedOrderNo);
        row.setGeneratedStatus("FAILED");
        row.setGeneratedMessage(msg);
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
    // Retry idempotency guard
    // ================================================================

    @Test
    void rowAlreadyInQueuedUspsIsSkippedEntirely() {
        // The row's leader is already in QUEUED_USPS from a prior run.
        // processGroup must skip it: no routing decision, no carrier call.
        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(queuedUspsRow(1, 8001)), "alice");

        assertEquals("success", resp.getStatus());
        OrderImportRowDTO row = resp.getData().getRows().get(0);
        // Guard must preserve the QUEUED_USPS state so the queue processor
        // bridge (G3b) can flip it to GENERATED / FAILED at drain time.
        assertEquals("QUEUED_USPS", row.getGeneratedStatus());
        assertEquals(8001, row.getGeneratedOrderNo());

        // No call to either collaborator: the guard fires at the very top
        // of processGroup, before validation / rate-limit / routing.
        verify(routing, never()).decide(anyLong(), any(), any());
        verifyNoInteractions(carrierService);
    }

    @Test
    void batchOfQueuedUspsRowsPreservesIdempotencyAcrossAllRows() {
        // Operator hits Retry on a batch where EVERY row is already
        // queued. The guard must fire per-row so nothing gets re-enqueued.
        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(queuedUspsRow(1, 8101), queuedUspsRow(2, 8102), queuedUspsRow(3, 8103)),
                "alice");

        List<OrderImportRowDTO> rows = resp.getData().getRows();
        for (OrderImportRowDTO r : rows) {
            assertEquals("QUEUED_USPS", r.getGeneratedStatus(),
                    "row " + r.getRowNumber() + " must stay QUEUED_USPS on retry");
        }
        verify(routing, never()).decide(anyLong(), any(), any());
        verifyNoInteractions(carrierService);
    }

    // ================================================================
    // FAILED-row retry — routing IS consulted, transitions cleanly
    // ================================================================

    @Test
    void failedRowRetryConsultsRoutingAndTransitionsToQueuedUspsWhenSingleQueued() {
        // Operator clicked Retry on a row that FAILED on the prior attempt
        // (e.g. a retry-exhaust from an earlier queue drain, or a transient
        // outage). Routing IS consulted (the guard only fires for
        // QUEUED_USPS, not FAILED), and returns SINGLE_QUEUED because the
        // queue's UNIQUE(shipment_id) permits a fresh insert for a
        // terminated prior row.
        when(routing.decide(eq(8201L), any(), any())).thenReturn(Optional.of(
                new RoutingDecision(RoutingDecision.Status.SINGLE_QUEUED, 999L, null, null)));

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(failedRow(1, 8201, "USPS OAuth 429 — retry exhausted")),
                "alice");

        OrderImportRowDTO row = resp.getData().getRows().get(0);
        assertEquals("QUEUED_USPS", row.getGeneratedStatus(),
                "Retry of a FAILED row must land on the queue when routing says so");
        assertEquals(8201, row.getGeneratedOrderNo(),
                "orderNo persists across the failed→queued transition");
        verify(routing, times(1)).decide(eq(8201L), any(), any());
        // Sync path stays uncalled — the queue is what will fire the label.
        verifyNoInteractions(carrierService);
    }

    @Test
    void failedRowRetryFallsThroughToSyncWhenRoutingReturnsEmpty() {
        // Same failed row, but the operator meanwhile flipped USPS_PROVIDER
        // back to STAMPS_COM — routing returns Optional.empty(), caller
        // must go through the sync generateManualLabel path.
        when(routing.decide(eq(8301L), any(), any())).thenReturn(Optional.empty());
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(okSync(8301L, "TN-8301"));

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(failedRow(1, 8301, "prior attempt failed")),
                "alice");

        assertEquals("GENERATED", resp.getData().getRows().get(0).getGeneratedStatus());
        verify(carrierService, times(1)).generateManualLabel(any(), any(), eq(8301));
    }

    // ================================================================
    // Mixed batch — QUEUED_USPS rows skipped, FAILED rows re-routed
    // ================================================================

    @Test
    void mixedRetryBatchSkipsQueuedRowsButReRoutesFailedRows() {
        // A retry batch with a queued row alongside a failed row. Queued
        // stays put; failed goes through routing again.
        when(routing.decide(eq(8401L), any(), any())).thenReturn(Optional.of(
                new RoutingDecision(RoutingDecision.Status.SINGLE_QUEUED, 777L, null, null)));

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(queuedUspsRow(1, 8402), failedRow(2, 8401, "retry me")),
                "alice");

        List<OrderImportRowDTO> rows = resp.getData().getRows();
        assertEquals("QUEUED_USPS", rows.get(0).getGeneratedStatus(),
                "the pre-queued row must stay queued");
        assertEquals("QUEUED_USPS", rows.get(1).getGeneratedStatus(),
                "the failed row got re-routed to the queue");

        // Only the failed row's orderNo reached the routing service.
        verify(routing, times(1)).decide(eq(8401L), any(), any());
        verify(routing, never()).decide(eq(8402L), any(), any());
    }
}
