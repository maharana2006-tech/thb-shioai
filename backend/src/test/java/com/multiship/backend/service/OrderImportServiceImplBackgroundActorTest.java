package com.multiship.backend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.OrderImportPreviewDTO;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.model.ImportGenerationJob;
import com.multiship.backend.repository.AuditLogRepository;
import com.multiship.backend.repository.ImportBatchRepository;
import com.multiship.backend.repository.ImportGenerationJobRepository;
import com.multiship.backend.service.carriers.usps.queue.UspsDirectRoutingService;
import com.multiship.backend.service.carriers.usps.queue.UspsDirectRoutingService.RoutingDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-G3a -- verifies the two background-context behaviors added on top of
 * PR-G2's routing wiring:
 *
 * <ol>
 *   <li><b>Actor stamping (audit log)</b> -- when {@code executeGenerationJob}
 *       runs a queued job whose row set landed on the USPS_DIRECT queue,
 *       the batch-summary {@code IMPORT_GENERATED} audit event is stamped
 *       with {@code system:import-worker/<workerId> on behalf of <original>}
 *       instead of the raw operator name. Sync-only runs (no queued rows)
 *       keep the raw operator name so we never fudge the actor on a
 *       non-USPS_DIRECT background run.</li>
 *   <li><b>Silent-fallback WARN alert (M-B3)</b> -- when a background-worker
 *       call to {@link UspsDirectRoutingService#decide} returns
 *       {@code Optional.empty()} for a row that WOULD have qualified for
 *       the queue (USPS carrier + USPS_PROVIDER=USPS_DIRECT), a WARN log
 *       row is emitted so ops can see the safety-net fallback burn USPS
 *       quota outside the 55/hr fair-scheduler.</li>
 * </ol>
 *
 * <p>Both behaviors are gated so operator-driven sync paths (jobId==null)
 * and non-USPS carriers see zero behavior change; those baselines are
 * asserted here too.
 */
class OrderImportServiceImplBackgroundActorTest {

    private final ObjectMapper json = new ObjectMapper();
    private final Map<Long, ImportBatch> batches = new HashMap<>();
    private final Map<Long, ImportGenerationJob> jobs = new HashMap<>();
    private final AtomicLong jobSeq = new AtomicLong(1);

    private CarrierService carrierService;
    private ImportGenerationJobRepository jobRepo;
    private UspsDirectRoutingService routing;
    private SystemSettingService systemSetting;
    private AuditService auditService;
    private OrderImportServiceImpl service;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        ImportBatchRepository batchRepo = mock(ImportBatchRepository.class);
        jobRepo = mock(ImportGenerationJobRepository.class);
        routing = mock(UspsDirectRoutingService.class);
        systemSetting = mock(SystemSettingService.class);
        // Use a real AuditService instance with a mocked repo so we can
        // capture the actor field on the persisted AuditLog row.
        AuditLogRepository auditRepo = mock(AuditLogRepository.class);
        when(auditRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        auditService = new AuditService(auditRepo);

        service = new OrderImportServiceImpl(carrierService);
        ReflectionTestUtils.setField(service, "importBatchRepository", batchRepo);
        ReflectionTestUtils.setField(service, "generationJobRepository", jobRepo);
        ReflectionTestUtils.setField(service, "importObjectMapper", json);
        ReflectionTestUtils.setField(service, "uspsDirectRoutingService", routing);
        ReflectionTestUtils.setField(service, "systemSettingService", systemSetting);
        ReflectionTestUtils.setField(service, "auditService", auditService);

        // Wire the tiny in-memory job + batch store so executeGenerationJob
        // reads back the same rows it wrote (matches ImportGenerationJobTest's
        // pattern verbatim).
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
        when(jobRepo.updateProgress(anyLong(), anyInt(), anyInt(), any(), any()))
                .thenAnswer(inv -> 1);
        when(jobRepo.isCancelRequested(anyLong())).thenReturn(false);

        // Capture WARN log rows from OrderImportServiceImpl so the M-B3
        // WARN branch can be asserted deterministically.
        Logger implLog = (Logger) LoggerFactory.getLogger(OrderImportServiceImpl.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        implLog.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        Logger implLog = (Logger) LoggerFactory.getLogger(OrderImportServiceImpl.class);
        implLog.detachAppender(logAppender);
    }

    // ================================================================
    // fixtures
    // ================================================================

    private ImportBatch batch(long id, String carrierCode) throws Exception {
        return batch(id, carrierCode, /*generatedOrderNo*/ 12345);
    }

    private ImportBatch batch(long id, String carrierCode, Integer generatedOrderNo) throws Exception {
        OrderImportRowDTO row = OrderImportRowDTO.builder()
                .rowNumber(1).orderRef("A" + id).clientCode("ACME")
                .recipientName("Jane").recipientPhone("2125550100")
                .addressLine1("42 Broadway").city("New York")
                .state("NY").postalCode("10001").countryCode("US")
                .carrierCode(carrierCode).accountNumber("A12345")
                .weight(new BigDecimal("2.5")).weightUnit("LB")
                .generatedOrderNo(generatedOrderNo)
                .build();
        ImportBatch b = new ImportBatch();
        b.setId(id);
        b.setStatus("INITIATE");
        b.setCreatedAt(LocalDateTime.now());
        b.setRowsJson(json.writeValueAsString(List.of(row)));
        b.setTotalRows(1);
        b.setFileName("test-import-" + id + ".csv");
        batches.put(id, b);
        return b;
    }

    private void seedJob(long importId, String requestedBy, String workerId) {
        ImportGenerationJob j = new ImportGenerationJob();
        j.setImportBatchId(importId);
        j.setStatus(ImportGenerationJob.QUEUED);
        j.setRequestedBy(requestedBy);
        j.setWorkerId(workerId);
        j.setProgressTotal(1);
        j.setCreatedAt(LocalDateTime.now());
        j.setId(jobSeq.getAndIncrement());
        jobs.put(j.getId(), j);
        // Simulate the worker's claim: mark IN_PROGRESS + RUNNING so
        // executeGenerationJob's status checks pass.
        batches.get(importId).setStatus("IN_PROGRESS");
        j.setStatus(ImportGenerationJob.RUNNING);
    }

    private void carrierSucceeds(long orderNo) {
        ApiResponse<LabelGenerationResponse> ok = ApiResponse.<LabelGenerationResponse>builder()
                .status("success").code(200)
                .data(LabelGenerationResponse.builder()
                        .orderNo(orderNo).trackingNumber("TN-" + orderNo).status("GENERATED").build())
                .build();
        when(carrierService.generateManualLabel(any(), any(), any())).thenReturn(ok);
    }

    private void uspsProviderActive(boolean active) {
        when(systemSetting.getDecrypted(eq("USPS_PROVIDER")))
                .thenReturn(active ? Optional.of("USPS_DIRECT") : Optional.of("STAMPS_COM"));
    }

    private List<OrderImportRowDTO> parseRows(ImportBatch b) throws Exception {
        return json.readValue(b.getRowsJson(), new TypeReference<List<OrderImportRowDTO>>() {});
    }

    // ================================================================
    // Actor stamping -- background-worker context, queued rows
    // ================================================================

    @Test
    void backgroundJobWithQueuedRowsStampsAuditActorAsSystemImportWorker() throws Exception {
        // Setup: routing service enqueues the USPS row. The job's requestedBy
        // is "alice" (the operator who clicked Generate before leaving); the
        // worker id is "host-a:1234:abc" (real workers stamp host:pid:uuid).
        batch(100L, "USPS");
        seedJob(100L, "alice", "host-a:1234:abc");
        uspsProviderActive(true);
        when(routing.decide(eq(12345L), any())).thenReturn(Optional.of(
                new RoutingDecision(RoutingDecision.Status.SINGLE_QUEUED, 42L, null, null)));

        service.executeGenerationJob(1L);

        // The IMPORT_GENERATED audit event must carry the stamped actor.
        // Real AuditService writes via the repo; capture the AuditLog row.
        AuditLogRepository auditRepo = (AuditLogRepository) ReflectionTestUtils.getField(auditService, "repo");
        assertNotNull(auditRepo, "audit repo should be wired");
        ArgumentCaptor<com.multiship.backend.model.AuditLog> cap =
                ArgumentCaptor.forClass(com.multiship.backend.model.AuditLog.class);
        verify(auditRepo, atLeastOnce()).save(cap.capture());
        com.multiship.backend.model.AuditLog importedGen = cap.getAllValues().stream()
                .filter(a -> AuditService.IMPORT_GENERATED.equals(a.getAction()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "IMPORT_GENERATED audit event must be recorded for queued background job"));
        assertEquals("system:import-worker/host-a:1234:abc on behalf of alice",
                importedGen.getActor(),
                "background context + queued rows must stamp the actor with worker id + original operator");
    }

    @Test
    void backgroundJobWithNoQueuedRowsKeepsRawRequestedByAsActor() throws Exception {
        // Same background context, but routing returns empty (SYNC) -- no
        // rows land on the queue. Actor stamping must NOT fire; sync-only
        // background runs keep the raw operator name so audit trails don't
        // falsely attribute non-queue activity to the worker.
        batch(101L, "FEDEX");
        seedJob(101L, "bob", "host-b:5678:def");
        uspsProviderActive(true);  // provider is USPS_DIRECT but row is FEDEX
        when(routing.decide(anyLong(), any())).thenReturn(Optional.empty());
        carrierSucceeds(12345L);

        service.executeGenerationJob(1L);

        AuditLogRepository auditRepo = (AuditLogRepository) ReflectionTestUtils.getField(auditService, "repo");
        ArgumentCaptor<com.multiship.backend.model.AuditLog> cap =
                ArgumentCaptor.forClass(com.multiship.backend.model.AuditLog.class);
        verify(auditRepo, atLeastOnce()).save(cap.capture());
        com.multiship.backend.model.AuditLog importedGen = cap.getAllValues().stream()
                .filter(a -> AuditService.IMPORT_GENERATED.equals(a.getAction()))
                .findFirst().orElseThrow(() -> new AssertionError("IMPORT_GENERATED not recorded"));
        assertEquals("bob", importedGen.getActor(),
                "sync-only background run must keep the raw requestedBy as actor");
    }

    // ================================================================
    // Actor stamping -- operator inline path, jobId == null
    // ================================================================

    @Test
    void operatorInlineRunKeepsRawUsernameAsActor() throws Exception {
        // The operator inline path invokes runGeneration with jobId == null,
        // so actor stamping must NEVER fire, even if rows land on the queue.
        // (This is the /orders/import/{id}/generate?wait=true path where the
        // operator is watching a progress spinner.)
        ImportBatch b = batch(102L, "USPS");
        // No seedJob -- inline path never creates a background job.
        uspsProviderActive(true);
        when(routing.decide(eq(12345L), any())).thenReturn(Optional.of(
                new RoutingDecision(RoutingDecision.Status.SINGLE_QUEUED, 77L, null, null)));

        // Directly call generateLabelsForBatch which reaches runGeneration
        // with jobId == null (matches enqueueGeneration's fallback path when
        // generationJobRepository is null; here it's set, so we use the
        // generateLabelsForBatch entry point).
        service.generateLabelsForBatch(102L, "carol", false, false, false);

        AuditLogRepository auditRepo = (AuditLogRepository) ReflectionTestUtils.getField(auditService, "repo");
        ArgumentCaptor<com.multiship.backend.model.AuditLog> cap =
                ArgumentCaptor.forClass(com.multiship.backend.model.AuditLog.class);
        verify(auditRepo, atLeastOnce()).save(cap.capture());
        com.multiship.backend.model.AuditLog importedGen = cap.getAllValues().stream()
                .filter(a -> AuditService.IMPORT_GENERATED.equals(a.getAction()))
                .findFirst().orElseThrow(() -> new AssertionError("IMPORT_GENERATED not recorded"));
        assertEquals("carol", importedGen.getActor(),
                "operator inline run must preserve the raw operator name -- never system:import-worker");
    }

    // ================================================================
    // M-B3 -- silent-fallback WARN under USPS_DIRECT + background context
    // ================================================================

    @Test
    void syncFallbackUnderUspsDirectAndBackgroundContextEmitsWarn() throws Exception {
        // The route qualifier: (a) background context, (b) row's carrier =
        // USPS, (c) USPS_PROVIDER=USPS_DIRECT. Routing returns Optional.empty()
        // (SYNC fallback -- e.g. missing order row race, splitter no-rows path).
        // WARN must fire, and the sync path must still execute so the label
        // is generated (M-B3 is loud alerting; blocking is G3b territory).
        batch(103L, "USPS");
        seedJob(103L, "alice", "host-c:9999:xyz");
        uspsProviderActive(true);
        when(routing.decide(anyLong(), any())).thenReturn(Optional.empty());
        carrierSucceeds(12345L);

        service.executeGenerationJob(1L);

        boolean warnFired = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .anyMatch(e -> {
                    String msg = e.getFormattedMessage();
                    return msg.contains("USPS Direct routing returned SYNC unexpectedly")
                            && msg.contains("parentOrderNo=12345")
                            && msg.contains("USPS_PROVIDER=USPS_DIRECT");
                });
        assertTrue(warnFired, "M-B3 WARN must fire with row + parentOrderNo. Captured: "
                + logAppender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage).toList());
        // Sync fallback still fires -- G3a doesn't block, only alerts.
        verify(carrierService, atLeastOnce()).generateManualLabel(any(), any(), any());
    }

    @Test
    void syncFallbackForNonUspsCarrierDoesNotEmitWarn() throws Exception {
        // Non-USPS carrier under USPS_DIRECT provider: routing correctly
        // returns SYNC because the carrier gate short-circuits (FedEx has
        // its own quota). NO WARN -- this is expected behavior, not a
        // safety-net fallback.
        batch(104L, "FEDEX");
        seedJob(104L, "alice", "host-d:1111:qqq");
        uspsProviderActive(true);
        when(routing.decide(anyLong(), any())).thenReturn(Optional.empty());
        carrierSucceeds(12345L);

        service.executeGenerationJob(1L);

        boolean warnFired = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .anyMatch(e -> e.getFormattedMessage().contains("USPS Direct routing returned SYNC unexpectedly"));
        assertTrue(!warnFired,
                "non-USPS carrier under USPS_DIRECT must NOT trigger the SYNC-fallback WARN");
    }

    @Test
    void syncFallbackWhenProviderIsStampsComDoesNotEmitWarn() throws Exception {
        // USPS carrier but USPS_PROVIDER=STAMPS_COM: routing returns SYNC
        // as intended. NO WARN -- the operator has explicitly disabled
        // USPS_DIRECT so the sync path is the correct route.
        batch(105L, "USPS");
        seedJob(105L, "alice", "host-e:2222:rrr");
        uspsProviderActive(false); // STAMPS_COM
        when(routing.decide(anyLong(), any())).thenReturn(Optional.empty());
        carrierSucceeds(12345L);

        service.executeGenerationJob(1L);

        boolean warnFired = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .anyMatch(e -> e.getFormattedMessage().contains("USPS Direct routing returned SYNC unexpectedly"));
        assertTrue(!warnFired,
                "USPS_PROVIDER=STAMPS_COM must NOT trigger the SYNC-fallback WARN even for USPS carriers");
    }

    @Test
    void syncFallbackInOperatorContextDoesNotEmitWarn() throws Exception {
        // USPS carrier, USPS_DIRECT provider, routing returns SYNC -- but
        // this is the operator inline path (jobId == null). NO WARN --
        // operator paths are noise-free; only the background worker gets
        // the loud alert (an operator can see the failure in the FE toast).
        batch(106L, "USPS");
        // No seedJob -- operator inline path.
        uspsProviderActive(true);
        when(routing.decide(anyLong(), any())).thenReturn(Optional.empty());
        carrierSucceeds(12345L);

        service.generateLabelsForBatch(106L, "dave", false, false, false);

        boolean warnFired = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .anyMatch(e -> e.getFormattedMessage().contains("USPS Direct routing returned SYNC unexpectedly"));
        assertTrue(!warnFired,
                "operator inline context must NOT trigger the SYNC-fallback WARN (only background workers do)");
        // But the sync fallback still fires as usual.
        verify(carrierService, atLeastOnce()).generateManualLabel(any(), any(), any());
    }

    @Test
    void backgroundEnqueuedRowDoesNotEmitTheSyncFallbackWarn() throws Exception {
        // Positive-path counter: background context + USPS_DIRECT provider
        // + USPS carrier, but routing returns SINGLE_QUEUED (the happy
        // path). NO WARN -- the row correctly reached the queue; the WARN
        // only fires on the fallback-to-sync path.
        batch(107L, "USPS");
        seedJob(107L, "alice", "host-f:3333:sss");
        uspsProviderActive(true);
        when(routing.decide(eq(12345L), any())).thenReturn(Optional.of(
                new RoutingDecision(RoutingDecision.Status.SINGLE_QUEUED, 55L, null, null)));

        service.executeGenerationJob(1L);

        boolean warnFired = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .anyMatch(e -> e.getFormattedMessage().contains("USPS Direct routing returned SYNC unexpectedly"));
        assertTrue(!warnFired,
                "successful queue enqueue must NOT trigger the fallback WARN (row landed on queue as expected)");
        // No sync call on the enqueue path -- the queue owns the label.
        verify(carrierService, never()).generateManualLabel(any(), any(), any());
    }
}
