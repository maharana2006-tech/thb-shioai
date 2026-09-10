package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.BulkLabelJobDTO;
import com.multiship.backend.dto.BulkLabelRequestDTO;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.model.BulkLabelJob;
import com.multiship.backend.repository.BulkLabelJobRepository;
import com.multiship.backend.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BulkLabelServiceImpl}. Mocks the label
 * generator + the JPA repository so the tests don't touch a real
 * database or hit carriers. Runs the worker synchronously via
 * runJob() so we can assert on the terminal state without racing the
 * dispatch executor.
 */
class BulkLabelServiceImplTest {

    private BulkLabelJobRepository jobRepo;
    private CarrierService carrierService;
    private OrderRepository orderRepo;
    private BulkLabelServiceImpl service;

    /** Track saved job states so we can inspect terminal state. */
    private final Map<Long, BulkLabelJob> saved = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        jobRepo = mock(BulkLabelJobRepository.class);
        carrierService = mock(CarrierService.class);
        orderRepo = mock(OrderRepository.class);

        // Simulate JPA save + findById against an in-memory Map.
        doAnswer(inv -> {
            BulkLabelJob j = inv.getArgument(0);
            if (j.getId() == null) j.setId(seq.getAndIncrement());
            saved.put(j.getId(), j);
            return j;
        }).when(jobRepo).save(any(BulkLabelJob.class));
        when(jobRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(saved.get(inv.<Long>getArgument(0))));

        // Bulk MEDIUM #9 + #14 — status() now uses a lightweight projection
        // to avoid dragging the ZIP through every 2s poll. Mirror the
        // in-memory map for the projection path so status() tests still
        // find the seeded rows.
        when(jobRepo.findSummaryById(anyLong()))
                .thenAnswer(inv -> {
                    BulkLabelJob j = saved.get(inv.<Long>getArgument(0));
                    if (j == null) return Optional.empty();
                    return Optional.of(summaryOf(j));
                });

        // Sprint 50 Tier 0.5 PR E - enforcer with flag OFF is a pure
        // pass-through, so existing test behavior is unchanged.
        service = new BulkLabelServiceImpl(jobRepo, carrierService, orderRepo,
                new TenantScopeEnforcer(new AccessScopePolicy(false)));
    }

    /** Build a projection-interface stand-in from an in-memory BulkLabelJob
     *  so the mocked {@code findSummaryById} can return one. Mirrors what
     *  Spring Data would produce from the native query. */
    private static com.multiship.backend.repository.BulkLabelJobRepository.BulkLabelJobSummary
            summaryOf(BulkLabelJob j) {
        return new com.multiship.backend.repository.BulkLabelJobRepository.BulkLabelJobSummary() {
            @Override public Long getId() { return j.getId(); }
            @Override public String getStatus() { return j.getStatus(); }
            @Override public int getTotalCount() { return j.getTotalCount(); }
            @Override public int getSuccessfulCount() { return j.getSuccessfulCount(); }
            @Override public int getFailedCount() { return j.getFailedCount(); }
            @Override public String getFailureMessage() { return j.getFailureMessage(); }
            @Override public java.time.LocalDateTime getCreatedAt() { return j.getCreatedAt(); }
            @Override public java.time.LocalDateTime getStartedAt() { return j.getStartedAt(); }
            @Override public java.time.LocalDateTime getCompletedAt() { return j.getCompletedAt(); }
            @Override public String getOrderNumbers() { return j.getOrderNumbers(); }
            @Override public String getRequestedBy() { return j.getRequestedBy(); }
            @Override public boolean getHasResultZip() {
                return j.getResultZipBase64() != null && !j.getResultZipBase64().isEmpty();
            }
        };
    }

    private static LabelGenerationResponse okLabel(long orderNo) {
        return LabelGenerationResponse.builder()
                .orderNo(orderNo)
                .trackingNumber("TN-" + orderNo)
                // Small canned PDF (as base64) so downloadLabelPdf returns bytes.
                .labelPdf(java.util.Base64.getEncoder().encodeToString(
                        ("PDF-CONTENT-" + orderNo).getBytes()))
                .status("GENERATED")
                .build();
    }

    private static LabelGenerationResponse failedLabel(long orderNo, String reason) {
        return LabelGenerationResponse.builder()
                .orderNo(orderNo)
                .trackingNumber(null)
                .message(reason)
                .build();
    }

    private void stubGenerate(long orderNo, LabelGenerationResponse label) {
        ApiResponse<LabelGenerationResponse> wrapped = ApiResponse
                .<LabelGenerationResponse>builder()
                .status("success").code(200).message("ok").data(label).build();
        when(carrierService.generateLabel(eq(orderNo), any(), anyString(), any()))
                .thenReturn(wrapped);
    }

    /* -------------------------- Request validation -------------------------- */

    @Test
    void submitRejectsEmptyOrderNumbers() {
        ApiResponse<BulkLabelJobDTO> resp = service.submit(
                BulkLabelRequestDTO.builder().orderNumbers(List.of()).build(),
                "alice");
        assertEquals("error", resp.getStatus());
        assertEquals(400, resp.getCode());
    }

    @Test
    void submitRejectsNullRequest() {
        ApiResponse<BulkLabelJobDTO> resp = service.submit(null, "alice");
        assertEquals(400, resp.getCode());
    }

    /**
     * Sprint 52 — batches over the platform bulk cap (500 orders) get a
     * 422 with BULK_LIMIT_EXCEEDED so the caller can programmatically
     * decide to split their batch. Under-cap counts stay on the happy path.
     */
    @Test
    void submitRejectsBatchesOver500OrdersWithBulkLimitExceeded() {
        java.util.List<Long> tooMany = new java.util.ArrayList<>();
        for (long i = 1; i <= 501; i++) tooMany.add(i);

        ApiResponse<BulkLabelJobDTO> resp = service.submit(
                BulkLabelRequestDTO.builder().orderNumbers(tooMany).build(),
                "alice");

        assertEquals("error", resp.getStatus());
        assertEquals(422, resp.getCode());
        assertEquals(com.multiship.backend.dto.ErrorCode.BULK_LIMIT_EXCEEDED.name(),
                resp.getErrorCode());
    }

    /* -------- Sprint 50 Tier 0.5 PR E: tenant-scope -------- */

    @Test
    void scopedUserCannotSubmitForeignTenantOrders() {
        // Arrange: put a scoped USER (ACME) in the security context.
        var authorities = List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, authorities);
        token.setDetails(new com.multiship.backend.config.JwtAuthenticationFilter.AuthDetails("ACME"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(token);
        try {
            BulkLabelServiceImpl scopedService = new BulkLabelServiceImpl(
                    jobRepo, carrierService, orderRepo,
                    new TenantScopeEnforcer(new AccessScopePolicy(true)));

            com.multiship.backend.model.Order foreignOrder = new com.multiship.backend.model.Order();
            foreignOrder.setOrderNo(7);
            foreignOrder.setTenantId("OTHER");
            when(orderRepo.findByOrderNo(7)).thenReturn(Optional.of(foreignOrder));

            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.security.access.AccessDeniedException.class,
                    () -> scopedService.submit(
                            BulkLabelRequestDTO.builder().orderNumbers(List.of(7L)).build(),
                            "acmeuser"));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    /* -------------------------- Worker -------------------------- */

    @Test
    void runJobHappyPathAllOrdersSucceed() throws Exception {
        stubGenerate(1L, okLabel(1L));
        stubGenerate(2L, okLabel(2L));
        stubGenerate(3L, okLabel(3L));

        // Create the job row manually so the dispatcher doesn't fire.
        BulkLabelJob job = new BulkLabelJob();
        job.setId(42L);
        job.setOrderNumbers("1,2,3");
        job.setTotalCount(3);
        job.setStatus("PENDING");
        job.setCreatedAt(java.time.LocalDateTime.now());
        saved.put(42L, job);

        service.runJob(42L);

        BulkLabelJob terminal = saved.get(42L);
        assertEquals("COMPLETED", terminal.getStatus());
        assertEquals(3, terminal.getSuccessfulCount());
        assertEquals(0, terminal.getFailedCount());
        assertNotNull(terminal.getResultZipBase64(),
                "Successful jobs must populate the ZIP payload");

        // Zip should contain 3 entries.
        byte[] zipBytes = java.util.Base64.getDecoder().decode(terminal.getResultZipBase64());
        int entryCount = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                assertTrue(entry.getName().endsWith(".pdf"),
                        "Entry name should end .pdf, got " + entry.getName());
                entryCount++;
            }
        }
        assertEquals(3, entryCount);
    }

    @Test
    void runJobMixedSuccessAndFailure() {
        stubGenerate(1L, okLabel(1L));
        stubGenerate(2L, failedLabel(2L, "no credentials"));
        stubGenerate(3L, okLabel(3L));

        BulkLabelJob job = new BulkLabelJob();
        job.setId(43L);
        job.setOrderNumbers("1,2,3");
        job.setTotalCount(3);
        job.setStatus("PENDING");
        job.setCreatedAt(java.time.LocalDateTime.now());
        saved.put(43L, job);

        service.runJob(43L);

        BulkLabelJob terminal = saved.get(43L);
        assertEquals("COMPLETED", terminal.getStatus(),
                "Per-order failure should NOT flip the whole job to FAILED");
        assertEquals(2, terminal.getSuccessfulCount());
        assertEquals(1, terminal.getFailedCount());
        assertNotNull(terminal.getFailureMessage(),
                "Failure message should list the bad order");
        assertTrue(terminal.getFailureMessage().contains("order 2"),
                "Failure summary should reference the failed order");
        assertTrue(terminal.getFailureMessage().contains("no credentials"));
    }

    @Test
    void runJobAllFailuresProducesNoZip() {
        stubGenerate(1L, failedLabel(1L, "boom"));
        stubGenerate(2L, failedLabel(2L, "boom"));

        BulkLabelJob job = new BulkLabelJob();
        job.setId(44L);
        job.setOrderNumbers("1,2");
        job.setTotalCount(2);
        job.setStatus("PENDING");
        job.setCreatedAt(java.time.LocalDateTime.now());
        saved.put(44L, job);

        service.runJob(44L);

        BulkLabelJob terminal = saved.get(44L);
        assertEquals("COMPLETED", terminal.getStatus());
        assertEquals(0, terminal.getSuccessfulCount());
        assertEquals(2, terminal.getFailedCount());
        assertNull(terminal.getResultZipBase64(),
                "Zip should be omitted when every order failed");
    }

    @Test
    void runJobExceptionFromGenerateLabelCountsAsFailure() {
        when(carrierService.generateLabel(eq(1L), any(), anyString(), any()))
                .thenThrow(new RuntimeException("boom"));
        stubGenerate(2L, okLabel(2L));

        BulkLabelJob job = new BulkLabelJob();
        job.setId(45L);
        job.setOrderNumbers("1,2");
        job.setTotalCount(2);
        job.setStatus("PENDING");
        job.setCreatedAt(java.time.LocalDateTime.now());
        saved.put(45L, job);

        service.runJob(45L);
        BulkLabelJob terminal = saved.get(45L);
        assertEquals("COMPLETED", terminal.getStatus(),
                "Uncaught exceptions from a single order shouldn't kill the job");
        assertEquals(1, terminal.getSuccessfulCount());
        assertEquals(1, terminal.getFailedCount());
    }

    @Test
    void runJobMissingJobRowIsHarmless() {
        // No job with this ID in the fake repo.
        service.runJob(999L);
        assertTrue(saved.isEmpty() || !saved.containsKey(999L));
    }

    /* -------------------------- downloadLabelPdf -------------------------- */

    @Test
    void downloadLabelPdfDecodesInlineBase64() {
        String content = "%PDF-STUB";
        LabelGenerationResponse label = LabelGenerationResponse.builder()
                .trackingNumber("TN")
                .labelPdf(java.util.Base64.getEncoder().encodeToString(content.getBytes()))
                .build();
        byte[] pdf = service.downloadLabelPdf(label);
        assertNotNull(pdf);
        assertEquals(content, new String(pdf));
    }

    @Test
    void downloadLabelPdfStripsDataUrlPrefix() {
        String base64 = java.util.Base64.getEncoder().encodeToString("%PDF-STUB".getBytes());
        LabelGenerationResponse label = LabelGenerationResponse.builder()
                .trackingNumber("TN")
                .labelPdf("data:application/pdf;base64," + base64)
                .build();
        assertNotNull(service.downloadLabelPdf(label));
    }

    @Test
    void downloadLabelPdfReturnsNullWhenBothInlineAndUrlBlank() {
        LabelGenerationResponse label = LabelGenerationResponse.builder()
                .trackingNumber("TN").build();
        assertNull(service.downloadLabelPdf(label));
    }

    /* -------------------------- Dedup + idempotency -------------------------- */

    /**
     * Bulk MEDIUM — duplicate orderNos in the submit request should be
     * silently deduped BEFORE persisting the job. Previously the second
     * occurrence would produce a duplicate entry in the ZIP and inflate
     * the successful count. Dedup preserves input order (LinkedHashSet).
     */
    @Test
    void submitDedupsDuplicateOrderNos() {
        ApiResponse<BulkLabelJobDTO> resp = service.submit(
                BulkLabelRequestDTO.builder()
                        .orderNumbers(java.util.List.of(42L, 43L, 42L, 44L, 43L))
                        .build(),
                "alice");

        assertEquals("success", resp.getStatus());
        // Post-dedup: {42, 43, 44} preserved in input order.
        BulkLabelJob persisted = saved.values().iterator().next();
        assertEquals(3, persisted.getTotalCount(),
                "Dedup must drop duplicates BEFORE persisting; totalCount reflects unique orders only");
        assertEquals("42,43,44", persisted.getOrderNumbers(),
                "Input ordering (LinkedHashSet) must be preserved after dedup");
    }

    /* -------------------------- Status polling -------------------------- */

    @Test
    void statusReturnsJobDto() {
        BulkLabelJob job = new BulkLabelJob();
        job.setId(50L);
        job.setStatus("RUNNING");
        job.setTotalCount(10);
        job.setSuccessfulCount(4);
        job.setFailedCount(1);
        job.setCreatedAt(java.time.LocalDateTime.now());
        saved.put(50L, job);

        ApiResponse<BulkLabelJobDTO> resp = service.status(50L);
        assertEquals("success", resp.getStatus());
        assertEquals("RUNNING", resp.getData().getStatus());
        assertEquals(4, resp.getData().getSuccessfulCount());
        assertEquals(1, resp.getData().getFailedCount());
    }

    @Test
    void statusReturns404ForUnknownJob() {
        ApiResponse<BulkLabelJobDTO> resp = service.status(9999L);
        assertEquals("error", resp.getStatus());
        assertEquals(404, resp.getCode());
    }

    @Test
    void toDtoReflectsDownloadableFlag() {
        BulkLabelJob job = new BulkLabelJob();
        job.setId(60L);
        job.setStatus("COMPLETED");
        assertFalse(BulkLabelServiceImpl.toDto(job).isDownloadable());
        job.setResultZipBase64("YWJj");
        assertTrue(BulkLabelServiceImpl.toDto(job).isDownloadable());
    }

    /* -------------------------- Regression: userDetails threading -------------------------- */

    /**
     * Regression guard for the null-userDetails bug that shipped in the
     * initial bulk-labels PR. runJob() must construct a UserDetails from
     * the persisted {@code requestedBy} and pass it into
     * {@link CarrierService#generateLabel} — otherwise
     * {@code CarrierServiceImpl.resolveUser} throws
     * "Authenticated user is required." on every order and the whole
     * batch silently fails 100%.
     *
     * <p>The other tests here use {@code any()} for the UserDetails
     * argument so a null there passes; this test captures the arg and
     * asserts it is non-null AND carries the persisted username.
     */
    @Test
    void runJobPassesUserDetailsFromRequestedBy() {
        stubGenerate(1L, okLabel(1L));

        BulkLabelJob job = new BulkLabelJob();
        job.setId(70L);
        job.setOrderNumbers("1");
        job.setTotalCount(1);
        job.setRequestedBy("alice@example.com");
        job.setStatus("PENDING");
        job.setCreatedAt(java.time.LocalDateTime.now());
        saved.put(70L, job);

        service.runJob(70L);

        org.mockito.ArgumentCaptor<org.springframework.security.core.userdetails.UserDetails> captor =
                org.mockito.ArgumentCaptor.forClass(
                        org.springframework.security.core.userdetails.UserDetails.class);
        // Bulk MED — idempotency key now includes jobId so different bulk
        // jobs for the same order don't silently reuse each other's
        // cached labels. Key shape is "bulk-{jobId}-{orderNo}".
        org.mockito.Mockito.verify(carrierService)
                .generateLabel(eq(1L), captor.capture(), eq("bulk-70-1"), org.mockito.ArgumentMatchers.isNull());

        org.springframework.security.core.userdetails.UserDetails passed = captor.getValue();
        assertNotNull(passed,
                "Bulk workers must pass a non-null UserDetails so CarrierServiceImpl.resolveUser succeeds");
        assertEquals("alice@example.com", passed.getUsername(),
                "The passed UserDetails must carry the operator who submitted the job");
    }

    /**
     * If somehow the job row was persisted with a blank requestedBy (shouldn't
     * happen — the controller substitutes "unknown"), we still pass a
     * non-null UserDetails so downstream resolveUser fails with
     * "User not found" (recoverable, per-order) rather than
     * "Authenticated user is required" (kills the whole batch).
     */
    /* -------------------------- Already-labeled pre-check -------------------------- */

    /**
     * Orders that already carry a generated label (either from manual or
     * from a prior bulk with a different idempotency key) MUST be counted
     * as SUCCESS with a note, not as failure. Without this pre-check,
     * CarrierServiceImpl.generateLabel returns 409 LABEL_ALREADY_GENERATED
     * and the pre-check-less bulk pipeline counted each as a failure.
     */
    @Test
    void alreadyLabeledOrdersAreCountedAsSuccessNotFailure() {
        // Wire OrderTrackingRepository via reflection since the test's
        // 4-arg constructor doesn't include it (field-injected @Autowired).
        var trackingRepo = org.mockito.Mockito.mock(
                com.multiship.backend.repository.OrderTrackingRepository.class);
        org.springframework.test.util.ReflectionTestUtils
                .setField(service, "orderTrackingRepository", trackingRepo);

        var existing = new com.multiship.backend.model.OrderTracking();
        existing.setOrderNo(42);
        existing.setIsLabelGenerated(true);
        existing.setStatus("GENERATED");
        existing.setTrackingNumber("TN-EXISTING-42");
        org.mockito.Mockito.when(trackingRepo.findByOrderNo(42))
                .thenReturn(java.util.Optional.of(existing));
        // Order 43 has no existing label — should proceed to carrier
        org.mockito.Mockito.when(trackingRepo.findByOrderNo(43))
                .thenReturn(java.util.Optional.empty());
        stubGenerate(43L, okLabel(43L));

        BulkLabelJob job = new BulkLabelJob();
        job.setId(100L);
        job.setOrderNumbers("42,43");
        job.setTotalCount(2);
        job.setStatus("PENDING");
        job.setCreatedAt(java.time.LocalDateTime.now());
        saved.put(100L, job);

        service.runJob(100L);

        BulkLabelJob terminal = saved.get(100L);
        assertEquals("COMPLETED", terminal.getStatus());
        assertEquals(2, terminal.getSuccessfulCount(),
                "Already-labeled order MUST be counted as success (not failure)");
        assertEquals(0, terminal.getFailedCount(),
                "Already-labeled order MUST NOT be counted as failure");
        assertNotNull(terminal.getFailureMessage(),
                "The summary should surface which orders were skipped");
        assertTrue(terminal.getFailureMessage().contains("order 42"),
                "The skipped order MUST be listed in the summary");
        assertTrue(terminal.getFailureMessage().contains("already had a label"),
                "Skip note should explain why we didn't re-generate");
        assertTrue(terminal.getFailureMessage().contains("TN-EXISTING-42"),
                "The existing tracking number should appear so operators can locate the label");

        // Verify the carrier was NEVER called for the skipped order.
        org.mockito.Mockito.verify(carrierService, org.mockito.Mockito.never())
                .generateLabel(eq(42L), any(), anyString(), any());
    }

    /* -------------------------- Cancellation -------------------------- */

    @Test
    void cancelUnknownJobReturns404() {
        ApiResponse<BulkLabelJobDTO> resp = service.cancel(9999L);
        assertEquals("error", resp.getStatus());
        assertEquals(404, resp.getCode());
        assertEquals("BULK_JOB_NOT_FOUND", resp.getErrorCode());
    }

    @Test
    void cancelJobInTerminalStateReturns409() {
        BulkLabelJob completed = new BulkLabelJob();
        completed.setId(90L);
        completed.setStatus("COMPLETED");
        saved.put(90L, completed);

        ApiResponse<BulkLabelJobDTO> resp = service.cancel(90L);
        assertEquals(409, resp.getCode());
        assertEquals("BULK_JOB_ALREADY_TERMINAL", resp.getErrorCode());
        assertTrue(resp.getMessage().contains("COMPLETED"));
    }

    @Test
    void cancelRunningJobFlipsToCancelledAfterWorkersDrain() {
        // Simulate a running job. cancel() sets the flag, then runJob's
        // finally block promotes status to CANCELLED. We drive both here
        // synchronously to observe the flip.
        stubGenerate(1L, okLabel(1L));
        stubGenerate(2L, okLabel(2L));

        BulkLabelJob job = new BulkLabelJob();
        job.setId(91L);
        job.setOrderNumbers("1,2");
        job.setTotalCount(2);
        job.setStatus("PENDING");
        job.setCreatedAt(java.time.LocalDateTime.now());
        saved.put(91L, job);

        // Cancel BEFORE runJob starts. Since processOneOrder gates on the
        // flag before hitting the carrier, no labels should be generated.
        ApiResponse<BulkLabelJobDTO> cancelResp = service.cancel(91L);
        assertEquals(200, cancelResp.getCode(),
                "cancel() on a non-terminal job returns 200 with the current DTO");

        service.runJob(91L);

        BulkLabelJob terminal = saved.get(91L);
        assertEquals("CANCELLED", terminal.getStatus(),
                "runJob() finally block must promote status to CANCELLED when the flag was set");
        assertEquals(0, terminal.getSuccessfulCount(),
                "No labels should be generated when the job was cancelled before dispatch");
        assertEquals(2, terminal.getFailedCount(),
                "Every skipped order should count as failed with a 'cancelled' reason");
        assertTrue(terminal.getFailureMessage().contains("cancelled"),
                "Failure summary should record the cancel reason for each skipped order");
    }

    /* -------------------------- Startup housekeeper -------------------------- */

    /**
     * Startup housekeeper flips stale RUNNING jobs (JVM-crash victims)
     * to FAILED with a diagnostic message. Uses the configurable
     * cutoff so tests don't need to fabricate 60-min-old timestamps.
     */
    @Test
    void reapStaleRunningJobsFlipsOldRunningRowsToFailed() {
        BulkLabelJob stale = new BulkLabelJob();
        stale.setId(80L);
        stale.setStatus("RUNNING");
        stale.setStartedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2));
        stale.setOrderNumbers("1,2,3");
        stale.setTotalCount(3);
        saved.put(80L, stale);
        org.mockito.Mockito.when(jobRepo.findByStatusAndStartedAtBeforeOrderByIdAsc(
                        org.mockito.ArgumentMatchers.eq("RUNNING"),
                        org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class)))
                .thenReturn(java.util.List.of(stale));

        int reaped = service.reapStaleRunningJobs();

        assertEquals(1, reaped);
        assertEquals("FAILED", stale.getStatus());
        assertNotNull(stale.getCompletedAt(), "Reaped jobs must get a completedAt timestamp");
        assertNotNull(stale.getFailureMessage());
        assertTrue(stale.getFailureMessage().contains("startup housekeeper"),
                "Reap message should mention the housekeeper for operator debugging");
    }

    @Test
    void reapStaleRunningJobsIsNoOpWhenClean() {
        org.mockito.Mockito.when(jobRepo.findByStatusAndStartedAtBeforeOrderByIdAsc(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class)))
                .thenReturn(java.util.List.of());

        int reaped = service.reapStaleRunningJobs();
        assertEquals(0, reaped);
    }

    /**
     * A stale row with an existing failureMessage (e.g. some workers reported
     * per-order failures before the crash) must retain that history — the
     * housekeeper appends to failureMessage rather than overwriting.
     */
    @Test
    void reapStaleRunningJobsPreservesExistingFailureMessage() {
        BulkLabelJob stale = new BulkLabelJob();
        stale.setId(81L);
        stale.setStatus("RUNNING");
        stale.setStartedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(3));
        stale.setFailureMessage("order 5: no credentials");
        saved.put(81L, stale);
        org.mockito.Mockito.when(jobRepo.findByStatusAndStartedAtBeforeOrderByIdAsc(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class)))
                .thenReturn(java.util.List.of(stale));

        service.reapStaleRunningJobs();

        assertTrue(stale.getFailureMessage().contains("order 5: no credentials"),
                "Pre-crash per-order failures must be preserved");
        assertTrue(stale.getFailureMessage().contains("startup housekeeper"),
                "Reap message must be appended, not replace");
    }

    @Test
    void runJobHandlesBlankRequestedByWithoutNpe() {
        stubGenerate(1L, okLabel(1L));

        BulkLabelJob job = new BulkLabelJob();
        job.setId(71L);
        job.setOrderNumbers("1");
        job.setTotalCount(1);
        job.setRequestedBy(null);      // deliberately blank
        job.setStatus("PENDING");
        job.setCreatedAt(java.time.LocalDateTime.now());
        saved.put(71L, job);

        service.runJob(71L);

        org.mockito.ArgumentCaptor<org.springframework.security.core.userdetails.UserDetails> captor =
                org.mockito.ArgumentCaptor.forClass(
                        org.springframework.security.core.userdetails.UserDetails.class);
        // Idempotency key includes the persisted jobId (71 in this fixture).
        org.mockito.Mockito.verify(carrierService)
                .generateLabel(eq(1L), captor.capture(), eq("bulk-71-1"), org.mockito.ArgumentMatchers.isNull());
        assertNotNull(captor.getValue());
        assertEquals("unknown", captor.getValue().getUsername());
    }
}
