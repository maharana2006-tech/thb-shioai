package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.dto.OrderImportPreviewDTO;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.service.carriers.usps.queue.IdempotencyKeys;
import com.multiship.backend.service.carriers.usps.queue.UspsDirectRoutingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-S5 (STAMPS_COM audit hardening) — pins the two S-track fields the
 * import path threads onto {@link ManualShipmentRequest} before the
 * {@code carrierService.generateManualLabel(...)} call for a Stamps carrier
 * row:
 *
 * <ol>
 *   <li><b>S3 D1 idempotency key</b> — when {@code existingOrderNo != null}
 *       the row must stamp {@code req.internalIdempotencyKey =
 *       IdempotencyKeys.forStampsOrder(orderNo)}. Net-new rows
 *       ({@code existingOrderNo == null}) leave the field null, matching the
 *       "no ambiguity to dedup against yet" contract.</li>
 *   <li><b>S3 D5 audit actor</b> — every processed row must stamp
 *       {@code req.internalAuditActor} to
 *       {@code system:import-operator} when {@code isBackgroundContext == false},
 *       or {@code system:import-worker/{batchId}} when it's true.</li>
 * </ol>
 *
 * <p>Also covers the row-status transitions that the S-track relies on:
 * successful generateManualLabel → row marked GENERATED with the tracking
 * number and orderNo; carrier failure → row marked FAILED with the humanised
 * error; retry re-runs failed rows through generateManualLabel again. Follows
 * the pattern from
 * {@link OrderImportServiceImplUspsDirectRoutingTest}. Routing service is
 * kept null so the Stamps rows fall straight through to sync (STAMPS_COM
 * doesn't route via the USPS_DIRECT queue — that's the whole point of the
 * S-track hardening the sync path separately).
 */
class OrderImportServiceImplStampsTest {

    private CarrierService carrierService;
    private OrderImportServiceImpl service;

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        service = new OrderImportServiceImpl(carrierService);
        // Routing service intentionally NOT wired — STAMPS_COM rows on the
        // sync path bypass the USPS_DIRECT queue. The routing-consult
        // behaviour is covered by
        // OrderImportServiceImplUspsDirectRoutingTest.
    }

    // ================================================================
    // fixtures
    // ================================================================

    private static OrderImportRowDTO stampsRow(int rowNumber, Integer generatedOrderNo) {
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
                .carrierCode("STAMPS")
                .accountNumber("A12345")
                .weight(new BigDecimal("1.5"))
                .weightUnit("LB")
                .generatedOrderNo(generatedOrderNo)
                .build();
    }

    private static ApiResponse<LabelGenerationResponse> okSync(long orderNo, String trackingNumber) {
        return ApiResponse.<LabelGenerationResponse>builder()
                .status("success").code(200).message("ok")
                .data(LabelGenerationResponse.builder()
                        .orderNo(orderNo).trackingNumber(trackingNumber)
                        .status("GENERATED").build())
                .build();
    }

    private static ApiResponse<LabelGenerationResponse> carrierFail(int orderNo, String msg) {
        return ApiResponse.<LabelGenerationResponse>builder()
                .status("error").code(502)
                .errorCode(ErrorCode.CARRIER_FAILURE.name())
                .message(msg)
                .data(LabelGenerationResponse.builder()
                        .orderNo((long) orderNo).status("ERROR").build())
                .build();
    }

    // ================================================================
    // Happy path — Stamps row succeeds → GENERATED with tracking number.
    // ================================================================

    @Test
    void successfulStampsRowMarkedGeneratedWithTracking() {
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(okSync(7001L, "9400-TN-7001"));

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(stampsRow(1, 7001)), "alice");

        assertEquals("success", resp.getStatus());
        OrderImportRowDTO row = resp.getData().getRows().get(0);
        assertEquals("GENERATED", row.getGeneratedStatus());
        assertEquals("9400-TN-7001", row.getGeneratedTrackingNumber());
        assertEquals(7001, row.getGeneratedOrderNo());
        verify(carrierService, times(1)).generateManualLabel(any(), any(), eq(7001));
    }

    // ================================================================
    // Carrier failure — row marked FAILED with the humanised message.
    // ================================================================

    @Test
    void stampsCarrierFailureMarksRowFailedWithMessage() {
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(carrierFail(7002, "Stamps.com rejected the request: postage insufficient"));

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(stampsRow(1, 7002)), "alice");

        OrderImportRowDTO row = resp.getData().getRows().get(0);
        assertEquals("FAILED", row.getGeneratedStatus());
        assertNotNull(row.getGeneratedMessage());
        assertTrue(row.getGeneratedMessage().toLowerCase().contains("postage")
                        || row.getGeneratedMessage().toLowerCase().contains("stamps"),
                "row message must surface the underlying carrier reason: "
                        + row.getGeneratedMessage());
        assertEquals(7002, row.getGeneratedOrderNo(),
                "ERROR order's number persists on the row so a retry reuses it");
    }

    // ================================================================
    // Retry-with-only-failed re-runs FAILED rows through
    // generateManualLabel again (same order number, second carrier call).
    // ================================================================

    @Test
    void retryReRunsFailedRowsThroughGenerateManualLabel() {
        // First attempt: carrier rejects → FAILED, orderNo captured.
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(carrierFail(7003, "Stamps SWSIM soft outage"));
        ApiResponse<OrderImportPreviewDTO> firstAttempt = service.commit(
                List.of(stampsRow(1, 7003)), "alice");

        OrderImportRowDTO firstRow = firstAttempt.getData().getRows().get(0);
        assertEquals("FAILED", firstRow.getGeneratedStatus());
        assertEquals(7003, firstRow.getGeneratedOrderNo());

        // Second attempt: SWSIM recovered, generateManualLabel succeeds.
        // The FAILED row carrying orderNo=7003 must be re-submitted through
        // generateManualLabel with existingOrderNo=7003 (UPDATE not INSERT).
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(okSync(7003L, "9400-TN-7003"));

        OrderImportRowDTO retryRow = stampsRow(1, 7003);
        retryRow.setGeneratedStatus("FAILED");
        retryRow.setGeneratedMessage("Stamps SWSIM soft outage");
        ApiResponse<OrderImportPreviewDTO> retry = service.commit(
                List.of(retryRow), "alice");

        OrderImportRowDTO after = retry.getData().getRows().get(0);
        assertEquals("GENERATED", after.getGeneratedStatus(),
                "retry with the FAILED row must re-run generateManualLabel and land GENERATED");
        assertEquals("9400-TN-7003", after.getGeneratedTrackingNumber());
        verify(carrierService, times(2)).generateManualLabel(any(), any(), eq(7003));
    }

    // ================================================================
    // S3 D1 — idempotency key stamping. Existing orderNo → key set;
    // net-new row → key null.
    // ================================================================

    @Test
    void existingOrderNoStampsCanonicalStampsIdempotencyKey() {
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(okSync(7004L, "9400-TN-7004"));

        service.commit(List.of(stampsRow(1, 7004)), "alice");

        ArgumentCaptor<ManualShipmentRequest> cap = ArgumentCaptor.forClass(ManualShipmentRequest.class);
        verify(carrierService).generateManualLabel(cap.capture(), any(), eq(7004));
        ManualShipmentRequest passed = cap.getValue();
        assertNotNull(passed.getInternalIdempotencyKey(),
                "S3 D1: existing orderNo must be stamped with an internal idempotency key");
        assertEquals(IdempotencyKeys.forStampsOrder(7004L),
                passed.getInternalIdempotencyKey(),
                "S3 D1: key must be the canonical order-anchored value from IdempotencyKeys.forStampsOrder");
    }

    @Test
    void netNewRowLeavesIdempotencyKeyNull() {
        // Net-new row has no orderNo yet → the S3 D1 stamp is intentionally
        // skipped (there's no ambiguity to dedup against). generateManualLabel
        // itself may mint one later via the client-driven header path.
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(okSync(7005L, "9400-TN-7005"));

        service.commit(List.of(stampsRow(1, null)), "alice");

        ArgumentCaptor<ManualShipmentRequest> cap = ArgumentCaptor.forClass(ManualShipmentRequest.class);
        verify(carrierService).generateManualLabel(cap.capture(), any(), eq(null));
        assertNull(cap.getValue().getInternalIdempotencyKey(),
                "S3 D1: net-new rows must NOT stamp an internal key (no orderNo to anchor to)");
    }

    // ================================================================
    // S3 D5 — audit actor stamping. Inline operator path uses
    // "system:import-operator"; the background variant lives in
    // OrderImportServiceImplBackgroundActorTest (needs job wiring).
    // ================================================================

    @Test
    void inlineOperatorRunStampsAuditActorAsImportOperator() {
        // service.commit(...) enters processGroup with isBackgroundContext=false
        // (the operator inline path). Actor must be system:import-operator.
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(okSync(7006L, "9400-TN-7006"));

        service.commit(List.of(stampsRow(1, 7006)), "alice");

        ArgumentCaptor<ManualShipmentRequest> cap = ArgumentCaptor.forClass(ManualShipmentRequest.class);
        verify(carrierService).generateManualLabel(cap.capture(), any(), anyInt());
        assertEquals("system:import-operator", cap.getValue().getInternalAuditActor(),
                "S3 D5: inline operator context must stamp system:import-operator regardless of orderNo");
    }

    @Test
    void inlineOperatorRunStampsActorEvenOnNetNewRow() {
        // The audit-actor stamp is unconditional (unlike the idempotency
        // key which is conditional on existingOrderNo). Net-new rows also
        // get the operator actor so the audit_log.actor column is never
        // NULL for import-driven Stamps calls.
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(okSync(7007L, "9400-TN-7007"));

        service.commit(List.of(stampsRow(1, null)), "alice");

        ArgumentCaptor<ManualShipmentRequest> cap = ArgumentCaptor.forClass(ManualShipmentRequest.class);
        verify(carrierService).generateManualLabel(cap.capture(), any(), eq(null));
        assertEquals("system:import-operator", cap.getValue().getInternalAuditActor(),
                "S3 D5: audit actor stamp must fire even for net-new rows");
    }

    // ================================================================
    // buildImportAuditActor helper — the private static that produces
    // the actor string. Pinning the shape directly so the two callers
    // (S3 D5 unit + BackgroundActorTest integration) can't drift apart.
    // ================================================================

    @Test
    void buildImportAuditActorForegroundReturnsImportOperator() throws Exception {
        String actor = invokeBuildActor(false, null);
        assertEquals("system:import-operator", actor);

        String actorWithBatch = invokeBuildActor(false, 42L);
        assertEquals("system:import-operator", actorWithBatch,
                "operator context ignores the batchId anchor — actor is always the same string");
    }

    @Test
    void buildImportAuditActorBackgroundReturnsImportWorkerWithBatchAnchor() throws Exception {
        String actor = invokeBuildActor(true, 42L);
        assertEquals("system:import-worker/42", actor,
                "S3 D5: background context stamps system:import-worker/{batchId}");

        String actorNoBatch = invokeBuildActor(true, null);
        assertEquals("system:import-worker/unknown", actorNoBatch,
                "S3 D5: missing batchId falls back to 'unknown' anchor (not NULL) so audit stays queryable");
    }

    private static String invokeBuildActor(boolean isBackgroundContext, Long jobId) throws Exception {
        java.lang.reflect.Method m = OrderImportServiceImpl.class.getDeclaredMethod(
                "buildImportAuditActor", boolean.class, Long.class);
        m.setAccessible(true);
        return (String) m.invoke(null, isBackgroundContext, jobId);
    }

    // ================================================================
    // Routing consult NOT fired for STAMPS_COM sync path (baseline for
    // the S-track — the whole point is that Stamps stays sync).
    // ================================================================

    @Test
    void stampsRowWithRoutingWiredButProviderStampsFallsThroughSync() {
        // Wire the routing service but have it return SYNC (Optional.empty)
        // for every decision — matches STAMPS_COM provider behaviour on the
        // shared routing service. Stamps rows must reach the sync
        // generateManualLabel call unchanged.
        UspsDirectRoutingService routing = mock(UspsDirectRoutingService.class);
        when(routing.decide(any(Long.class), any(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(service, "uspsDirectRoutingService", routing);

        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(okSync(7008L, "9400-TN-7008"));

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(stampsRow(1, 7008)), "alice");

        assertEquals("GENERATED", resp.getData().getRows().get(0).getGeneratedStatus());
        verify(carrierService, times(1)).generateManualLabel(any(), any(), eq(7008));
    }
}
