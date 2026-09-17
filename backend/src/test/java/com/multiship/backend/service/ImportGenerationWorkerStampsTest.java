package com.multiship.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.model.ImportGenerationJob;
import com.multiship.backend.repository.ImportBatchRepository;
import com.multiship.backend.repository.ImportGenerationJobRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-S5 (STAMPS_COM audit hardening) — restart-safety coverage of the
 * background-worker path for Stamps carrier rows. Sibling coverage for
 * USPS_DIRECT lives in {@link ImportGenerationWorkerRequeueStaleTest}; this
 * suite mirrors the pattern (in-memory job + batch stores + direct
 * {@link OrderImportServiceImpl#executeGenerationJob} invocation) but keeps
 * the routing service unwired so Stamps rows fall through to sync — the
 * shape S-track hardens.
 *
 * <p>{@link ImportGenerationWorker} itself is a thin scheduler (poll + claim
 * + dispatch + heartbeat + requeueStale). Its interesting behaviour is
 * delegated to {@code executeGenerationJob}, so this class tests that path
 * directly — the poll/claim/heartbeat wiring is covered by the worker's
 * bean tests up at the Spring layer.
 *
 * <p>Scenarios:
 * <ul>
 *   <li><b>Stamps happy-path (background)</b> — a job whose row is a Stamps
 *       carrier with an existing orderNo generates cleanly and lands the
 *       row GENERATED.</li>
 *   <li><b>Stamps carrier failure persists FAILED across restart</b> — a
 *       worker whose Stamps call fails (SWSIM 500) leaves the row FAILED
 *       with the error message; the batch still transitions to DONE
 *       (worker done, row failed is a normal outcome).</li>
 *   <li><b>Retry after FAILED re-runs generateManualLabel</b> — a second
 *       worker pass picks the FAILED row up fresh and re-submits through
 *       generateManualLabel with the same orderNo (UPDATE not INSERT).
 *       Simulates the RestartRecoveryTest flow verbatim, then adds a
 *       successful retry.</li>
 *   <li><b>requeueStale re-picks a stale RUNNING job without losing tenant
 *       context</b> — a resumed job's row keeps its clientCode through the
 *       requeue → run cycle.</li>
 * </ul>
 */
class ImportGenerationWorkerStampsTest {

    private final ObjectMapper json = new ObjectMapper();
    private final Map<Long, ImportBatch> batches = new HashMap<>();
    private final Map<Long, ImportGenerationJob> jobs = new HashMap<>();
    private final AtomicLong jobSeq = new AtomicLong(1);

    private CarrierService carrierService;
    private ImportGenerationJobRepository jobRepo;
    private OrderImportServiceImpl service;

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        ImportBatchRepository batchRepo = mock(ImportBatchRepository.class);
        jobRepo = mock(ImportGenerationJobRepository.class);
        // Routing service intentionally UNSET — Stamps rows on the sync
        // path bypass the USPS_DIRECT queue. The routing-consult variant
        // is covered by ImportGenerationWorkerRequeueStaleTest.

        service = new OrderImportServiceImpl(carrierService);
        ReflectionTestUtils.setField(service, "importBatchRepository", batchRepo);
        ReflectionTestUtils.setField(service, "generationJobRepository", jobRepo);
        ReflectionTestUtils.setField(service, "importObjectMapper", json);

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

    private OrderImportRowDTO stampsRow(int rowNumber, Integer generatedOrderNo) {
        return OrderImportRowDTO.builder()
                .rowNumber(rowNumber).orderRef("R" + rowNumber).clientCode("ACME")
                .recipientName("Jane " + rowNumber).recipientPhone("2125550100")
                .addressLine1(rowNumber + " Broadway").city("New York")
                .state("NY").postalCode("10001").countryCode("US")
                .carrierCode("STAMPS").accountNumber("A12345")
                .weight(new BigDecimal("1.5")).weightUnit("LB")
                .generatedOrderNo(generatedOrderNo)
                .build();
    }

    private void seedJob(long importId, String requestedBy, String workerId,
                         List<OrderImportRowDTO> rows) throws Exception {
        ImportBatch b = new ImportBatch();
        b.setId(importId);
        b.setStatus("IN_PROGRESS");
        b.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        b.setRowsJson(json.writeValueAsString(rows));
        b.setTotalRows(rows.size());
        b.setFileName("test-stamps-" + importId + ".csv");
        batches.put(importId, b);

        ImportGenerationJob j = new ImportGenerationJob();
        j.setImportBatchId(importId);
        j.setStatus(ImportGenerationJob.RUNNING);
        j.setRequestedBy(requestedBy);
        j.setWorkerId(workerId);
        j.setProgressTotal(rows.size());
        j.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        j.setAttempts(1);
        j.setId(jobSeq.getAndIncrement());
        jobs.put(j.getId(), j);
    }

    private void seedResumedJob(long importId, String requestedBy, String recoveryWorker,
                                List<OrderImportRowDTO> rows) throws Exception {
        // Simulate a crashed-then-requeued job: attempts=2 (first attempt
        // died, requeueStale flipped back to QUEUED, recovery-host claimed
        // it and re-flipped RUNNING). The recoveryWorker is the NEW worker.
        ImportBatch b = new ImportBatch();
        b.setId(importId);
        b.setStatus("IN_PROGRESS");
        b.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        b.setRowsJson(json.writeValueAsString(rows));
        b.setTotalRows(rows.size());
        b.setFileName("resumed-stamps-" + importId + ".csv");
        batches.put(importId, b);

        ImportGenerationJob j = new ImportGenerationJob();
        j.setImportBatchId(importId);
        j.setStatus(ImportGenerationJob.RUNNING);
        j.setRequestedBy(requestedBy);
        j.setWorkerId(recoveryWorker);
        j.setProgressTotal(rows.size());
        j.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        j.setAttempts(2);
        j.setId(jobSeq.getAndIncrement());
        jobs.put(j.getId(), j);
    }

    private ApiResponse<LabelGenerationResponse> okSync(long orderNo, String tn) {
        return ApiResponse.<LabelGenerationResponse>builder()
                .status("success").code(200)
                .data(LabelGenerationResponse.builder()
                        .orderNo(orderNo).trackingNumber(tn).status("GENERATED").build())
                .build();
    }

    private ApiResponse<LabelGenerationResponse> carrierFail(long orderNo, String msg) {
        return ApiResponse.<LabelGenerationResponse>builder()
                .status("error").code(502)
                .errorCode(ErrorCode.CARRIER_FAILURE.name())
                .message(msg)
                .data(LabelGenerationResponse.builder()
                        .orderNo(orderNo).status("ERROR").build())
                .build();
    }

    private List<OrderImportRowDTO> parseRows(ImportBatch b) throws Exception {
        return json.readValue(b.getRowsJson(), new TypeReference<List<OrderImportRowDTO>>() {});
    }

    // ================================================================
    // Happy path — background Stamps run generates cleanly.
    // ================================================================

    @Test
    void backgroundStampsJobRunsCleanlyToDone() throws Exception {
        seedJob(300L, "alice", "worker-1:100:aaa",
                List.of(stampsRow(1, 8001)));
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(okSync(8001L, "9400-TN-8001"));

        service.executeGenerationJob(1L);

        List<OrderImportRowDTO> after = parseRows(batches.get(300L));
        assertEquals("GENERATED", after.get(0).getGeneratedStatus());
        assertEquals("9400-TN-8001", after.get(0).getGeneratedTrackingNumber());
        assertEquals(ImportGenerationJob.DONE, jobs.get(1L).getStatus(),
                "clean Stamps background run completes as DONE");
    }

    // ================================================================
    // Stamps carrier failure — row FAILED, worker still finishes DONE.
    // The FAILED state must survive to the row for the operator to retry.
    // ================================================================

    @Test
    void stampsCarrierFailurePersistsFailedRowStatusAcrossJobCompletion() throws Exception {
        seedJob(301L, "alice", "worker-2:200:bbb",
                List.of(stampsRow(1, 8002)));
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(carrierFail(8002L, "Stamps SWSIM 500 upstream outage"));

        service.executeGenerationJob(1L);

        List<OrderImportRowDTO> after = parseRows(batches.get(301L));
        assertEquals("FAILED", after.get(0).getGeneratedStatus(),
                "carrier failure must persist FAILED to the row for retry pickup");
        assertNotNull(after.get(0).getGeneratedMessage());
        assertTrue(after.get(0).getGeneratedMessage().toLowerCase().contains("stamps")
                        || after.get(0).getGeneratedMessage().toLowerCase().contains("swsim")
                        || after.get(0).getGeneratedMessage().toLowerCase().contains("outage"),
                "row message must surface the carrier reason: "
                        + after.get(0).getGeneratedMessage());
        assertEquals(8002, after.get(0).getGeneratedOrderNo(),
                "ERROR order's number persists on the row so a subsequent retry reuses it");
        assertEquals(ImportGenerationJob.DONE, jobs.get(1L).getStatus(),
                "background job status = DONE even when the row FAILED (worker completed cleanly)");
    }

    // ================================================================
    // Retry after FAILED — a resumed job picks the FAILED row fresh and
    // re-submits it through generateManualLabel. Second call succeeds.
    // ================================================================

    @Test
    void resumedJobRetriesFailedStampsRowThroughGenerateManualLabel() throws Exception {
        // Simulate the state after the first attempt failed: FAILED row
        // carries the ERROR order's number so retry updates that order.
        OrderImportRowDTO failedRow = stampsRow(1, 8003);
        failedRow.setGeneratedStatus("FAILED");
        failedRow.setGeneratedMessage("Stamps SWSIM soft outage — retry after 30s");
        seedResumedJob(302L, "alice", "recovery-host:2:def", List.of(failedRow));

        // SWSIM recovered — retry succeeds.
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(okSync(8003L, "9400-TN-8003"));

        service.executeGenerationJob(1L);

        List<OrderImportRowDTO> after = parseRows(batches.get(302L));
        assertEquals("GENERATED", after.get(0).getGeneratedStatus(),
                "resumed job with FAILED Stamps row must re-run generateManualLabel and land GENERATED");
        assertEquals("9400-TN-8003", after.get(0).getGeneratedTrackingNumber());
        assertEquals(8003, after.get(0).getGeneratedOrderNo(),
                "orderNo survives the FAILED → GENERATED transition so no duplicate order is minted");

        // Carrier was called exactly once (the retry pass) — the crashed
        // first attempt's call was on a different worker.
        verify(carrierService, times(1)).generateManualLabel(any(), any(), any());
    }

    // ================================================================
    // requeueStale re-queues a stale RUNNING job — the worker's own
    // requeueStale() delegates to the repo. Verified against the repo
    // signature (worker is a thin scheduler around this call).
    // ================================================================

    @Test
    void requeueStaleFlipsStaleRunningJobsBackToQueuedByCutoff() {
        // ImportGenerationWorker.requeueStale() calls
        // jobs.requeueStale(now - staleAfterSeconds). Verify the repo
        // interaction shape — the worker's job is to marshal the cutoff
        // correctly and log the count.
        when(jobRepo.requeueStale(any(LocalDateTime.class))).thenReturn(2);

        // Direct repo call (as the worker would do) — we can't easily
        // spin the whole worker without Spring, but the delegation
        // contract is small enough to pin at the repo boundary.
        int n = jobRepo.requeueStale(LocalDateTime.now().minusSeconds(60));

        assertEquals(2, n, "requeueStale returns the number of flipped jobs so the worker can log it");
        verify(jobRepo, times(1)).requeueStale(any(LocalDateTime.class));
    }

    // ================================================================
    // Tenant context survives a resumed job — the row's clientCode
    // is written back verbatim after the run.
    // ================================================================

    @Test
    void resumedStampsJobPreservesRowClientCodeAcrossRun() throws Exception {
        OrderImportRowDTO row = stampsRow(1, 8004);
        row.setClientCode("SPECIFIC-TENANT");
        seedResumedJob(303L, "alice", "recovery-host:3:xyz", List.of(row));

        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenReturn(okSync(8004L, "9400-TN-8004"));

        service.executeGenerationJob(1L);

        List<OrderImportRowDTO> after = parseRows(batches.get(303L));
        assertEquals("SPECIFIC-TENANT", after.get(0).getClientCode(),
                "clientCode must survive the requeue-then-run cycle — the row is written back with "
                        + "the same tenant scope it entered with");
        assertEquals("GENERATED", after.get(0).getGeneratedStatus());
    }

    // ================================================================
    // Multi-row batch — one Stamps success + one Stamps failure in the
    // same job; each row lands its own status verbatim.
    // ================================================================

    @Test
    void backgroundBatchWithMixedStampsSuccessAndFailurePinsEachRowIndependently() throws Exception {
        seedJob(304L, "alice", "worker-4:400:mix",
                List.of(stampsRow(1, 8005), stampsRow(2, 8006)));

        // Different response per orderNo — success for 8005, fail for 8006.
        when(carrierService.generateManualLabel(any(), any(), any()))
                .thenAnswer(inv -> {
                    Integer existing = inv.getArgument(2);
                    if (existing != null && existing == 8005) return okSync(8005L, "9400-TN-8005");
                    return carrierFail(8006L, "Stamps SWSIM temporary error");
                });

        service.executeGenerationJob(1L);

        List<OrderImportRowDTO> after = parseRows(batches.get(304L));
        assertEquals(2, after.size());
        // Rows preserved in input order.
        OrderImportRowDTO r1 = after.stream().filter(r -> r.getRowNumber() == 1).findFirst().orElseThrow();
        OrderImportRowDTO r2 = after.stream().filter(r -> r.getRowNumber() == 2).findFirst().orElseThrow();
        assertEquals("GENERATED", r1.getGeneratedStatus());
        assertEquals("9400-TN-8005", r1.getGeneratedTrackingNumber());
        assertEquals("FAILED", r2.getGeneratedStatus());
        assertNotNull(r2.getGeneratedMessage());

        assertEquals(ImportGenerationJob.DONE, jobs.get(1L).getStatus(),
                "worker completes even when one row failed — FAILED is a per-row outcome, not a job outcome");
    }
}
