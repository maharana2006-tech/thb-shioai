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

        // Sprint 50 Tier 0.5 PR E - enforcer with flag OFF is a pure
        // pass-through, so existing test behavior is unchanged.
        service = new BulkLabelServiceImpl(jobRepo, carrierService, orderRepo,
                new TenantScopeEnforcer(new AccessScopePolicy(false)));
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
}
