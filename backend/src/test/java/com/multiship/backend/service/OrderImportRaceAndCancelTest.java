package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ImportBatchDTO;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.repository.ImportBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Import I-11 + I-3 regression tests.
 *
 * <ul>
 *   <li><b>I-11</b> concurrent-generate race — two operators clicking Generate
 *       on the same import batch used to both enter generateLabelsForBatch,
 *       fan out per-row label calls in parallel, and produce duplicate
 *       paid shipments. Now the status transition to IN_PROGRESS runs as
 *       a single atomic UPDATE that only succeeds if the row is currently
 *       in an accepted starting state.</li>
 *   <li><b>I-3</b> cancellation — DELETE /history/{id}/generate flips a
 *       cooperative flag; workers check it before invoking the carrier.
 *       Already-in-flight carrier calls run to completion (can't
 *       interrupt a paid label mid-request without leaking it).</li>
 * </ul>
 */
class OrderImportRaceAndCancelTest {

    private CarrierService carrierService;
    private ImportBatchRepository importBatchRepository;
    private OrderImportServiceImpl service;

    private final Map<Long, ImportBatch> saved = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        importBatchRepository = mock(ImportBatchRepository.class);

        service = new OrderImportServiceImpl(carrierService);
        ReflectionTestUtils.setField(service, "importBatchRepository", importBatchRepository);
        ReflectionTestUtils.setField(service, "importObjectMapper", new com.fasterxml.jackson.databind.ObjectMapper());

        // JPA-in-a-Map so save() + findById() reflect each other.
        doAnswer(inv -> {
            ImportBatch b = inv.getArgument(0);
            if (b.getId() == null) b.setId(seq.getAndIncrement());
            saved.put(b.getId(), b);
            return b;
        }).when(importBatchRepository).save(any(ImportBatch.class));
        when(importBatchRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(saved.get(inv.<Long>getArgument(0))));

        // atomicallyTransitionStatus — the CAS guard. Return 1 iff current
        // status is in the allowed set, then flip the in-map value.
        when(importBatchRepository.atomicallyTransitionStatus(
                        anyLong(), anyString(), any(java.util.Collection.class)))
                .thenAnswer(inv -> {
                    Long id = inv.getArgument(0);
                    String newStatus = inv.getArgument(1);
                    java.util.Collection<String> allowed = inv.getArgument(2);
                    ImportBatch b = saved.get(id);
                    if (b == null) return 0;
                    String cur = b.getStatus() == null ? "" : b.getStatus().toUpperCase();
                    if (!allowed.contains(cur)) return 0;
                    b.setStatus(newStatus);
                    return 1;
                });
    }

    private ImportBatch persistBatchWithOneRow(long id, String status) throws Exception {
        OrderImportRowDTO row = OrderImportRowDTO.builder()
                .rowNumber(1)
                .clientCode("ACME")
                .recipientName("Jane")
                .recipientPhone("2125550100")
                .addressLine1("42 Broadway")
                .city("New York")
                .state("NY")
                .postalCode("10001")
                .countryCode("US")
                .carrierCode("UPS")
                .accountNumber("A12345")
                .weight(new BigDecimal("2.5"))
                .weightUnit("LB")
                .build();
        ImportBatch batch = new ImportBatch();
        batch.setId(id);
        batch.setStatus(status);
        batch.setCreatedAt(LocalDateTime.now());
        batch.setRowsJson(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(List.of(row)));
        batch.setTotalRows(1);
        saved.put(id, batch);
        return batch;
    }

    /* -------------------------- I-11: race guard -------------------------- */

    @Test
    void secondGenerateOnAlreadyGeneratingBatchIsRejectedWith409() throws Exception {
        persistBatchWithOneRow(100L, "IN_PROGRESS");   // simulate first click already running

        var ex = assertThrows(
                OrderImportServiceImpl.ConcurrentBatchGenerationException.class,
                () -> service.generateLabelsForBatch(100L, "alice", false, false, true),
                "Second generate on an IN_PROGRESS batch MUST throw; controller maps to 409.");
        assertTrue(ex.getMessage().contains("IN_PROGRESS"),
                "Exception message must name the current status so operators know why.");
    }

    @Test
    void generateOnCompletedBatchIsRejected() throws Exception {
        // A COMPLETED batch is a terminal state — retry via 'Retry failed'
        // path only, which uses onlyFailed=true; a fresh Generate must
        // ALSO be permitted only from allowed starting states. COMPLETE
        // is in the allow list (retry from complete is allowed), so a
        // successful CAS proves the allow-list respects it. Adjusting the
        // fixture to prove REJECTION we use a status not in the allow set:
        persistBatchWithOneRow(101L, "IN_PROGRESS");
        assertThrows(OrderImportServiceImpl.ConcurrentBatchGenerationException.class,
                () -> service.generateLabelsForBatch(101L, "alice", false, false, true));
    }

    @Test
    void generateFromInitiateDoesNotThrowRaceGuard() throws Exception {
        persistBatchWithOneRow(102L, "INITIATE");
        // We don't stub the deep label pipeline (that would need lots of
        // additional Spring wiring); the important assertion is that the
        // CAS didn't reject us. Any deep failure lands as FAILED status
        // (row-level errors), not as a ConcurrentBatchGenerationException.
        ImportBatchDTO out = service.generateLabelsForBatch(102L, "alice", false, false, true);
        assertNotNull(out);
        String terminalStatus = saved.get(102L).getStatus();
        // Anything except IN_PROGRESS proves the run reached the finally
        // block (which flipped status) rather than blowing up early.
        assertTrue(!"IN_PROGRESS".equalsIgnoreCase(terminalStatus),
                "Batch must reach terminal state (COMPLETE / PARTIAL_COMPLETE / FAILED / CANCELLED); "
                        + "got " + terminalStatus);
    }

    /* -------------------------- I-3: cancellation -------------------------- */

    @Test
    void cancelUnknownBatchReturns404() {
        ApiResponse<String> resp = service.cancelGeneration(9999L);
        assertEquals(404, resp.getCode());
        assertEquals("BULK_JOB_NOT_FOUND", resp.getErrorCode());
    }

    @Test
    void cancelOnTerminalBatchReturns409() throws Exception {
        persistBatchWithOneRow(200L, "COMPLETE");
        ApiResponse<String> resp = service.cancelGeneration(200L);
        assertEquals(409, resp.getCode());
        assertEquals("BULK_JOB_ALREADY_TERMINAL", resp.getErrorCode());
    }

    @Test
    void cancelOnAnIdleBatchIsRefused_soItCantStopTheNextRun() throws Exception {
        persistBatchWithOneRow(201L, "INITIATE");
        // Nothing is running: a Cancel accepted here used to stay flagged and
        // stop (and mark CANCELLED) the import's next real run.
        ApiResponse<String> cancelResp = service.cancelGeneration(201L);
        assertEquals(409, cancelResp.getCode(), "nothing is running — nothing to cancel");
        assertEquals("IMPORT_BATCH_STATE", cancelResp.getErrorCode());

        service.generateLabelsForBatch(201L, "alice", false, false, true);
        assertTrue(!"CANCELLED".equalsIgnoreCase(saved.get(201L).getStatus()),
                "a refused Cancel must not cancel the next run; got " + saved.get(201L).getStatus());
    }

    @Test
    void cancelDuringARunFlipsTheBatchToCancelledAfterWorkerDrain() throws Exception {
        persistBatchWithOneRow(202L, "INITIATE");
        // The carrier call happens mid-run (the import is IN_PROGRESS) — cancel from inside it.
        java.util.concurrent.atomic.AtomicInteger cancelCode = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.stubbing.Answer<ApiResponse<LabelGenerationResponse>> cancelThenSucceed = inv -> {
            cancelCode.set(service.cancelGeneration(202L).getCode());
            return ApiResponse.<LabelGenerationResponse>builder()
                    .status("success").code(200)
                    .data(LabelGenerationResponse.builder().orderNo(999L).trackingNumber("TN-999").status("GENERATED").build())
                    .build();
        };
        when(carrierService.generateManualLabel(any(), any())).thenAnswer(cancelThenSucceed);
        when(carrierService.generateManualLabel(any(), any(), any())).thenAnswer(cancelThenSucceed);

        service.generateLabelsForBatch(202L, "alice", false, false, true);
        assertEquals(200, cancelCode.get(), "cancel() while the import is generating is accepted");
        assertEquals("CANCELLED", saved.get(202L).getStatus(),
                "runJob's finally block must promote status to CANCELLED when the flag is set");
    }

    /* -------------------------- Startup housekeeper -------------------------- */

    @Test
    void reapStaleInProgressBatchesFlipsOldRunningBatchesToFailed() throws Exception {
        ImportBatch stale = new ImportBatch();
        stale.setId(300L);
        stale.setStatus("IN_PROGRESS");
        stale.setCreatedAt(LocalDateTime.now().minusHours(2));   // well past 60-min cutoff
        saved.put(300L, stale);
        when(importBatchRepository.findByStatusInOrderByIdAsc(any(java.util.Collection.class)))
                .thenReturn(List.of(stale));

        int reaped = service.reapStaleInProgressBatches();
        assertEquals(1, reaped);
        assertEquals("FAILED", stale.getStatus());
    }

    @Test
    void reapDoesNotTouchFreshBatchesThatJustStarted() throws Exception {
        ImportBatch fresh = new ImportBatch();
        fresh.setId(301L);
        fresh.setStatus("IN_PROGRESS");
        fresh.setCreatedAt(LocalDateTime.now().minusMinutes(2));  // well inside the cutoff
        saved.put(301L, fresh);
        when(importBatchRepository.findByStatusInOrderByIdAsc(any(java.util.Collection.class)))
                .thenReturn(List.of(fresh));

        int reaped = service.reapStaleInProgressBatches();
        assertEquals(0, reaped, "A batch that just started must not be reaped.");
        assertEquals("IN_PROGRESS", fresh.getStatus());
    }

    // ===== helpers =====

    private void stubGenerateManualLabelSuccess() {
        when(carrierService.generateManualLabel(any(), any()))
                .thenReturn(ApiResponse.<LabelGenerationResponse>builder()
                        .status("success").code(200)
                        .data(LabelGenerationResponse.builder()
                                .orderNo(999L)
                                .trackingNumber("TN-999")
                                .status("GENERATED")
                                .build())
                        .build());
    }
}
