package com.multiship.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.model.ImportGenerationJob;
import com.multiship.backend.repository.ImportBatchRepository;
import com.multiship.backend.repository.ImportGenerationJobRepository;
import com.multiship.backend.service.carriers.usps.queue.UspsDirectRoutingService;
import com.multiship.backend.service.carriers.usps.queue.UspsDirectRoutingService.RoutingDecision;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PR-G3a M-B2 -- restart-safety verification tests.
 *
 * <p>Scenario: {@link ImportGenerationWorker#requeueStale()} re-picks a
 * job whose worker crashed mid-run. The re-run enters
 * {@link OrderImportServiceImpl#executeGenerationJob(Long)} again, and
 * {@code processGroup} sees rows the crashed attempt already touched (some
 * QUEUED_USPS, some FAILED, some untouched PENDING). This test pins the
 * per-row behavior so a restart-then-re-run never:
 *
 * <ul>
 *   <li>double-enqueues rows already parked on the queue (would trip the
 *       UNIQUE(shipment_id) constraint AND double-count queue slots);</li>
 *   <li>skips rows that a crashed worker never got to (they must reach
 *       {@code routing.decide()} on the re-run);</li>
 *   <li>refuses to retry rows that FAILED after the prior queue attempt
 *       exhausted (their queue row is terminal and gone -- a fresh
 *       enqueue is safe and correct).</li>
 * </ul>
 *
 * <p>These are pure-Mockito tests -- no real DB, no real ImportGenerationWorker
 * bean. We simulate the re-picked job by seeding an ImportBatch with a
 * rowsJson payload that carries the pre-crash statuses and calling
 * {@code executeGenerationJob(jobId)} directly.
 */
class ImportGenerationWorkerRequeueStaleTest {

    private final ObjectMapper json = new ObjectMapper();
    private final Map<Long, ImportBatch> batches = new HashMap<>();
    private final Map<Long, ImportGenerationJob> jobs = new HashMap<>();
    private final AtomicLong jobSeq = new AtomicLong(1);

    private CarrierService carrierService;
    private ImportGenerationJobRepository jobRepo;
    private UspsDirectRoutingService routing;
    private OrderImportServiceImpl service;

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        ImportBatchRepository batchRepo = mock(ImportBatchRepository.class);
        jobRepo = mock(ImportGenerationJobRepository.class);
        routing = mock(UspsDirectRoutingService.class);

        service = new OrderImportServiceImpl(carrierService);
        ReflectionTestUtils.setField(service, "importBatchRepository", batchRepo);
        ReflectionTestUtils.setField(service, "generationJobRepository", jobRepo);
        ReflectionTestUtils.setField(service, "importObjectMapper", json);
        ReflectionTestUtils.setField(service, "uspsDirectRoutingService", routing);

        doAnswer(inv -> { ImportBatch b = inv.getArgument(0); batches.put(b.getId(), b); return b; })
                .when(batchRepo).save(any(ImportBatch.class));
        when(batchRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(batches.get(inv.<Long>getArgument(0))));
        when(batchRepo.atomicallyTransitionStatus(anyLong(), anyString(), any(java.util.Collection.class)))
                .thenAnswer(inv -> {
                    ImportBatch b = batches.get(inv.<Long>getArgument(0));
                    java.util.Collection<String> allowed = inv.getArgument(2);
                    if (b == null || !allowed.contains(b.getStatus())) return 0;
                    b.setStatus(inv.getArgument(1));
                    return 1;
                });

        doAnswer(inv -> {
            ImportGenerationJob j = inv.getArgument(0);
            if (j.getId() == null) j.setId(jobSeq.getAndIncrement());
            jobs.put(j.getId(), j);
            return j;
        }).when(jobRepo).save(any(ImportGenerationJob.class));
        when(jobRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(jobs.get(inv.<Long>getArgument(0))));
        when(jobRepo.updateProgress(anyLong(), anyInt(), anyInt(), any(), any())).thenReturn(1);
        when(jobRepo.isCancelRequested(anyLong())).thenReturn(false);
    }

    // ================================================================
    // fixtures
    // ================================================================

    private OrderImportRowDTO baseRow(int rowNumber, int generatedOrderNo) {
        return OrderImportRowDTO.builder()
                .rowNumber(rowNumber).orderRef("R" + rowNumber).clientCode("ACME")
                .recipientName("Jane " + rowNumber).recipientPhone("2125550100")
                .addressLine1(rowNumber + " Broadway").city("New York")
                .state("NY").postalCode("10001").countryCode("US")
                .carrierCode("USPS").accountNumber("A12345")
                .weight(new BigDecimal("1.5")).weightUnit("LB")
                .generatedOrderNo(generatedOrderNo)
                .build();
    }

    private void seedResumedJob(long importId, String rowStatus, String rowMessage,
                                int generatedOrderNo) throws Exception {
        // Simulate a job that was RUNNING when the worker crashed, and whose
        // requeueStale() (elsewhere) flipped back to QUEUED for another worker
        // to pick up. We then simulate that pick-up by calling
        // executeGenerationJob directly.
        OrderImportRowDTO row = baseRow(1, generatedOrderNo);
        row.setGeneratedStatus(rowStatus);
        row.setGeneratedMessage(rowMessage);

        ImportBatch b = new ImportBatch();
        b.setId(importId);
        b.setStatus("IN_PROGRESS"); // requeueStale doesn't touch the batch status
        b.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        b.setRowsJson(json.writeValueAsString(List.of(row)));
        b.setTotalRows(1);
        b.setFileName("resumed-import-" + importId + ".csv");
        batches.put(importId, b);

        // The re-picked job: originally RUNNING under worker "crashed-host",
        // now QUEUED again via requeueStale, about to be claimed by a fresh
        // worker "recovery-host". By the time executeGenerationJob runs, the
        // (new) worker has already flipped it to RUNNING with its own id.
        ImportGenerationJob j = new ImportGenerationJob();
        j.setImportBatchId(importId);
        j.setStatus(ImportGenerationJob.RUNNING);
        j.setRequestedBy("alice");
        j.setWorkerId("recovery-host:2:def");
        j.setProgressTotal(1);
        j.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        j.setAttempts(2); // 2nd attempt after the crash
        j.setId(jobSeq.getAndIncrement());
        jobs.put(j.getId(), j);
    }

    private void carrierSucceeds(long orderNo) {
        ApiResponse<LabelGenerationResponse> ok = ApiResponse.<LabelGenerationResponse>builder()
                .status("success").code(200)
                .data(LabelGenerationResponse.builder()
                        .orderNo(orderNo).trackingNumber("TN-" + orderNo).status("GENERATED").build())
                .build();
        when(carrierService.generateManualLabel(any(), any(), any())).thenReturn(ok);
    }

    // ================================================================
    // Case A: leader row is in QUEUED_USPS -- guard fires, no rework
    // ================================================================

    @Test
    void resumedJobWithQueuedUspsRowSkipsRoutingAndCarrier() throws Exception {
        seedResumedJob(200L, "QUEUED_USPS",
                "Queued USPS Direct label (item 42) -- waiting.", 9001);

        service.executeGenerationJob(1L);

        // The row is already on the queue -- neither the routing service nor
        // the carrier should be consulted. The guard at the top of processGroup
        // (PR-G2 M-I1) returns GroupOutcome(queued=true) immediately.
        verifyNoInteractions(routing);
        verifyNoInteractions(carrierService);

        // Row stays in QUEUED_USPS (the queue processor bridge in G3b flips
        // it later). The job itself reaches DONE without incident.
        ImportBatch b = batches.get(200L);
        List<OrderImportRowDTO> after = json.readValue(b.getRowsJson(),
                new TypeReference<List<OrderImportRowDTO>>() {});
        assertEquals("QUEUED_USPS", after.get(0).getGeneratedStatus(),
                "resumed job must NOT reset an already-queued row -- G3b bridge owns the transition");
        assertEquals(ImportGenerationJob.DONE, jobs.get(1L).getStatus(),
                "resumed job completes cleanly when every row was already on the queue");
    }

    // ================================================================
    // Case B: leader row is PENDING (untouched by crashed attempt)
    // ================================================================

    @Test
    void resumedJobWithPendingRowInvokesRoutingAndEnqueuesNormally() throws Exception {
        // The crashed attempt never got to this row. On the re-run, routing
        // is consulted for the first time and returns SINGLE_QUEUED --
        // exactly the behavior of a fresh (never-crashed) run.
        seedResumedJob(201L, null, null, 9002);
        when(routing.decide(eq(9002L), any(), any())).thenReturn(Optional.of(
                new RoutingDecision(RoutingDecision.Status.SINGLE_QUEUED, 500L, null, null)));

        service.executeGenerationJob(1L);

        verify(routing, times(1)).decide(eq(9002L), any(), any());
        verifyNoInteractions(carrierService);

        List<OrderImportRowDTO> after = json.readValue(batches.get(201L).getRowsJson(),
                new TypeReference<List<OrderImportRowDTO>>() {});
        assertEquals("QUEUED_USPS", after.get(0).getGeneratedStatus(),
                "PENDING rows on a resumed run must go through routing and land on the queue");
        assertTrue(after.get(0).getGeneratedMessage() != null
                        && after.get(0).getGeneratedMessage().contains("500"),
                "queue item id must appear in the row message");
    }

    // ================================================================
    // Case C: leader row is FAILED (prior queue exhaust) -- retry re-enqueues
    // ================================================================

    @Test
    void resumedJobWithFailedRowInvokesRoutingAndReEnqueuesCleanly() throws Exception {
        // On the crashed attempt, this row's queue exhausted (55/hr slot cap
        // ran out mid-batch) and left the row FAILED. The queue row itself
        // is terminal, so a fresh enqueue on the retry is safe (the UNIQUE
        // constraint only bites for LIVE queue rows -- terminal rows don't
        // block a new one for the same shipment_id).
        seedResumedJob(202L, "FAILED",
                "USPS OAuth 429 -- retry exhausted after 4 automatic passes", 9003);
        when(routing.decide(eq(9003L), any(), any())).thenReturn(Optional.of(
                new RoutingDecision(RoutingDecision.Status.SINGLE_QUEUED, 501L, null, null)));

        service.executeGenerationJob(1L);

        // Routing IS consulted (the guard only fires for QUEUED_USPS, not
        // FAILED), and returns a fresh queue item id.
        verify(routing, times(1)).decide(eq(9003L), any(), any());
        verifyNoInteractions(carrierService);

        List<OrderImportRowDTO> after = json.readValue(batches.get(202L).getRowsJson(),
                new TypeReference<List<OrderImportRowDTO>>() {});
        assertEquals("QUEUED_USPS", after.get(0).getGeneratedStatus(),
                "FAILED row on a resumed run must be re-routed to the queue");
        assertEquals(9003, after.get(0).getGeneratedOrderNo(),
                "orderNo persists across the failed->queued transition (guaranteed by retry idempotency)");
    }
}
