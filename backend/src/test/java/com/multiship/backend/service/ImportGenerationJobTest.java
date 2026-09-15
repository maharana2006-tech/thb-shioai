package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ImportBatchDTO;
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
import java.util.Collection;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Label generation as a durable background job: enqueue, run, cancel, recover. */
class ImportGenerationJobTest {

    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
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
        service = new OrderImportServiceImpl(carrierService);
        ReflectionTestUtils.setField(service, "importBatchRepository", batchRepo);
        ReflectionTestUtils.setField(service, "generationJobRepository", jobRepo);
        ReflectionTestUtils.setField(service, "importObjectMapper", json);

        doAnswer(inv -> { ImportBatch b = inv.getArgument(0); batches.put(b.getId(), b); return b; })
                .when(batchRepo).save(any(ImportBatch.class));
        when(batchRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(batches.get(inv.<Long>getArgument(0))));
        when(batchRepo.atomicallyTransitionStatus(anyLong(), anyString(), any(Collection.class))).thenAnswer(inv -> {
            ImportBatch b = batches.get(inv.<Long>getArgument(0));
            Collection<String> allowed = inv.getArgument(2);
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
        when(jobRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(jobs.get(inv.<Long>getArgument(0))));
        when(jobRepo.findFirstByImportBatchIdOrderByIdDesc(anyLong())).thenAnswer(inv -> jobs.values().stream()
                .filter(j -> j.getImportBatchId().equals(inv.<Long>getArgument(0)))
                .max((a, b) -> Long.compare(a.getId(), b.getId())));
        when(jobRepo.isCancelRequested(anyLong())).thenAnswer(inv -> {
            ImportGenerationJob j = jobs.get(inv.<Long>getArgument(0));
            return j != null && j.isCancelRequested();
        });
        when(jobRepo.updateProgress(anyLong(), anyInt(), anyInt(), any(), any())).thenAnswer(inv -> {
            ImportGenerationJob j = jobs.get(inv.<Long>getArgument(0));
            if (j == null) return 0;
            j.setProgressDone(inv.getArgument(1));
            j.setProgressTotal(inv.getArgument(2));
            return 1;
        });
    }

    private ImportBatch batch(long id, String status, String clientCode) throws Exception {
        OrderImportRowDTO row = OrderImportRowDTO.builder().rowNumber(1).orderRef("A").clientCode(clientCode)
                .recipientName("Jane").recipientPhone("2125550100").addressLine1("42 Broadway").city("New York")
                .state("NY").postalCode("10001").countryCode("US").carrierCode("UPS").accountNumber("A12345")
                .weight(new BigDecimal("2.5")).weightUnit("LB").build();
        ImportBatch b = new ImportBatch();
        b.setId(id);
        b.setStatus(status);
        b.setCreatedAt(LocalDateTime.now());
        b.setRowsJson(json.writeValueAsString(List.of(row)));
        b.setTotalRows(1);
        batches.put(id, b);
        return b;
    }

    private void carrierSucceeds() {
        ApiResponse<LabelGenerationResponse> ok = ApiResponse.<LabelGenerationResponse>builder().status("success").code(200)
                .data(LabelGenerationResponse.builder().orderNo(990L).trackingNumber("TN-990").status("GENERATED").build())
                .build();
        when(carrierService.generateManualLabel(any(), any())).thenReturn(ok);
        when(carrierService.generateManualLabel(any(), any(), any())).thenReturn(ok);
    }

    @Test
    void enqueueClaimsTheImportAndQueuesAJob_withoutCallingTheCarrier() throws Exception {
        batch(1, "INITIATE", "ACME");
        ImportBatchDTO dto = service.enqueueGeneration(1L, "alice", false, false, false);

        assertEquals("IN_PROGRESS", dto.getStatus());
        assertNotNull(batches.get(1L).getGenerationStartedAt(), "the claim stamps the start");
        ImportGenerationJob job = jobs.values().iterator().next();
        assertEquals(ImportGenerationJob.QUEUED, job.getStatus());
        assertEquals(1L, job.getImportBatchId());
        assertEquals("alice", job.getRequestedBy());
        assertEquals(1, job.getProgressTotal());
        verify(carrierService, never()).generateManualLabel(any(), any());
        verify(carrierService, never()).generateManualLabel(any(), any(), any());
    }

    @Test
    void refusalsStaySynchronous_andQueueNothing() throws Exception {
        ImportBatch b = batch(2, "INITIATE", "ACME");
        b.setDeletedAt(LocalDateTime.now());
        OrderImportServiceImpl.ImportBatchStateException e = assertThrows(OrderImportServiceImpl.ImportBatchStateException.class,
                () -> service.enqueueGeneration(2L, "alice", false, false, false));
        assertEquals(409, e.getStatus());

        batch(3, "IN_PROGRESS", "ACME");
        assertThrows(OrderImportServiceImpl.ConcurrentBatchGenerationException.class,
                () -> service.enqueueGeneration(3L, "alice", false, false, false));
        verify(jobRepo, never()).save(any(ImportGenerationJob.class));
    }

    @Test
    void aQueuedJobRunsToDone_andRecordsTheOutcome() throws Exception {
        batch(4, "INITIATE", "ACME");
        carrierSucceeds();
        service.enqueueGeneration(4L, "alice", false, false, false);
        Long jobId = jobs.keySet().iterator().next();

        service.executeGenerationJob(jobId);

        ImportGenerationJob job = jobs.get(jobId);
        assertEquals(ImportGenerationJob.DONE, job.getStatus());
        assertEquals("COMPLETE", job.getResultStatus());
        assertTrue(job.getResultMessage() != null && job.getResultMessage().startsWith("1 of 1 order labelled"),
                "result message: " + job.getResultMessage());
        assertNotNull(job.getFinishedAt());
        assertEquals("COMPLETE", batches.get(4L).getStatus());

        OrderImportService.GenProgressView view = service.generationProgress(4L);
        assertTrue(!view.running(), "a finished job reports not running");
        assertEquals(ImportGenerationJob.DONE, view.jobStatus());
        assertEquals("COMPLETE", view.resultStatus());
    }

    @Test
    void cancelFlaggedOnTheJobRowStopsTheRun() throws Exception {
        batch(5, "INITIATE", "ACME");
        carrierSucceeds();
        service.enqueueGeneration(5L, "alice", false, false, false);
        Long jobId = jobs.keySet().iterator().next();
        jobs.get(jobId).setCancelRequested(true);   // e.g. Cancel handled by another server

        service.executeGenerationJob(jobId);

        assertEquals("CANCELLED", batches.get(5L).getStatus());
        assertEquals(ImportGenerationJob.CANCELLED, jobs.get(jobId).getStatus());
    }

    @Test
    void aJobWhoseImportIsNoLongerGeneratingFailsWithoutCallingTheCarrier() throws Exception {
        batch(6, "INITIATE", "ACME");
        service.enqueueGeneration(6L, "alice", false, false, false);
        Long jobId = jobs.keySet().iterator().next();
        batches.get(6L).setStatus("COMPLETE");

        service.executeGenerationJob(jobId);

        assertEquals(ImportGenerationJob.FAILED, jobs.get(jobId).getStatus());
        verify(carrierService, never()).generateManualLabel(any(), any());
        verify(carrierService, never()).generateManualLabel(any(), any(), any());
    }

    @Test
    void rowsOutsideTheQueuedScopeFailTheJobSafely() throws Exception {
        batch(7, "INITIATE", "OTHER");
        service.enqueueGeneration(7L, "alice", false, false, false);
        Long jobId = jobs.keySet().iterator().next();
        jobs.get(jobId).setRequestedScope("ACME");   // queued by an ACME-scoped user

        service.executeGenerationJob(jobId);

        assertEquals(ImportGenerationJob.FAILED, jobs.get(jobId).getStatus());
        assertEquals("FAILED", batches.get(7L).getStatus(), "the import isn't left IN_PROGRESS");
        verify(carrierService, never()).generateManualLabel(any(), any());
        verify(carrierService, never()).generateManualLabel(any(), any(), any());
    }

    @Test
    void aResumedJobSkipsOrdersACrashedAttemptAlreadyLabelled() throws Exception {
        ImportBatch b = batch(9, "INITIATE", "ACME");
        List<OrderImportRowDTO> rows = json.readValue(b.getRowsJson(),
                new com.fasterxml.jackson.core.type.TypeReference<List<OrderImportRowDTO>>() {});
        rows.get(0).setBatchId(555);   // label batch minted at upload
        b.setRowsJson(json.writeValueAsString(rows));
        carrierSucceeds();

        service.enqueueGeneration(9L, "alice", false, false, false);
        assertEquals(555, batches.get(9L).getLabelBatchId(), "the claim fixes the label batch before the run");

        // The first attempt labelled order "A" in batch 555, then the server died
        // before the row set was saved. The resumed job must not send it again.
        ImportBatchRepository batchRepo = (ImportBatchRepository) ReflectionTestUtils.getField(service, "importBatchRepository");
        when(batchRepo.findOrdersInLabelBatchByCustomerRefIn(eq(555), any()))
                .thenReturn(List.<Object[]>of(new Object[]{900555, "A", "GENERATED"}));
        service.executeGenerationJob(jobs.keySet().iterator().next());

        verify(carrierService, never()).generateManualLabel(any(), any());
        verify(carrierService, never()).generateManualLabel(any(), any(), any());
        assertEquals("COMPLETE", batches.get(9L).getStatus());
    }

    @Test
    void startupReaperLeavesImportsWithALiveJobToTheQueue() throws Exception {
        ImportBatch b = batch(8, "IN_PROGRESS", "ACME");
        b.setCreatedAt(LocalDateTime.now().minusHours(3));
        ImportBatchRepository batchRepo = (ImportBatchRepository) ReflectionTestUtils.getField(service, "importBatchRepository");
        when(batchRepo.findByStatusInOrderByIdAsc(any(Collection.class))).thenReturn(List.of(b));
        when(jobRepo.existsByImportBatchIdAndStatusIn(anyLong(), any(Collection.class))).thenReturn(true);

        assertEquals(0, service.reapStaleInProgressBatches());
        assertEquals("IN_PROGRESS", b.getStatus());
    }
}
