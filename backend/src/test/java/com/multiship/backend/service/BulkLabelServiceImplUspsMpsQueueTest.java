package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.model.BulkLabelJob;
import com.multiship.backend.model.Order;
import com.multiship.backend.repository.BulkLabelJobRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService;
import com.multiship.backend.service.carriers.usps.queue.UspsMpsSplitterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
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
 * PR-F2 - unit tests for the MPS-branch in
 * {@link BulkLabelServiceImpl#maybeEnqueueUspsDirect(long)}. Mirrors
 * the pure-Mockito style of
 * {@link BulkLabelServiceImplUspsQueueTest}.
 *
 * <p>Matrix:
 * <table><thead><tr><th>Order</th><th>Splitter wired</th>
 * <th>Expected</th></tr></thead><tbody>
 * <tr><td>USPS + packageCount=5</td><td>yes</td><td>MPS route (5 pieces)</td></tr>
 * <tr><td>USPS + packageCount=1</td><td>yes</td><td>Single-label enqueue (PR-F1)</td></tr>
 * <tr><td>USPS + packageCount=5</td><td>no (unwired)</td><td>Single-label enqueue (fallback)</td></tr>
 * <tr><td>USPS + packageCount=5 + splitter throws</td><td>yes</td><td>sync fallback</td></tr>
 * </tbody></table>
 */
class BulkLabelServiceImplUspsMpsQueueTest {

    private BulkLabelJobRepository jobRepo;
    private CarrierService carrierService;
    private OrderRepository orderRepo;
    private SystemSettingService settings;
    private UspsLabelQueueService queue;
    private UspsMpsSplitterService splitter;
    private BulkLabelServiceImpl service;

    private final Map<Long, BulkLabelJob> saved = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        jobRepo = mock(BulkLabelJobRepository.class);
        carrierService = mock(CarrierService.class);
        orderRepo = mock(OrderRepository.class);
        settings = mock(SystemSettingService.class);
        queue = mock(UspsLabelQueueService.class);
        splitter = mock(UspsMpsSplitterService.class);

        doAnswer(inv -> {
            BulkLabelJob j = inv.getArgument(0);
            if (j.getId() == null) j.setId(seq.getAndIncrement());
            saved.put(j.getId(), j);
            return j;
        }).when(jobRepo).save(any(BulkLabelJob.class));
        when(jobRepo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(saved.get(inv.<Long>getArgument(0))));

        service = new BulkLabelServiceImpl(jobRepo, carrierService, orderRepo,
                new TenantScopeEnforcer(new AccessScopePolicy(false)));
        service.setUspsLabelQueueService(queue);
        service.setSystemSettingService(settings);
        service.setUspsMpsSplitterService(splitter);
    }

    /* -------------------------- helpers -------------------------- */

    private void stubProvider(String value) {
        when(settings.getDecrypted("USPS_PROVIDER")).thenReturn(Optional.ofNullable(value));
    }

    private void stubOrder(int orderNo, String shipviaCd, String tenantId, Integer packageCount) {
        Order o = new Order();
        o.setOrderNo(orderNo);
        o.setShipviaCd(shipviaCd);
        o.setTenantId(tenantId);
        o.setPackageCount(packageCount);
        when(orderRepo.findByOrderNo(orderNo)).thenReturn(Optional.of(o));
    }

    private BulkLabelJob seedJob(long jobId, String orderNumbersCsv, int totalCount) {
        BulkLabelJob job = new BulkLabelJob();
        job.setId(jobId);
        job.setOrderNumbers(orderNumbersCsv);
        job.setTotalCount(totalCount);
        job.setStatus("PENDING");
        job.setCreatedAt(LocalDateTime.now());
        saved.put(jobId, job);
        return job;
    }

    /* -------------------------- Matrix -------------------------- */

    @Test
    void mpsOrderRoutesThroughSplitter_notSingleEnqueue() {
        stubProvider("USPS_DIRECT");
        stubOrder(500, "USPS", "ACME", 5);   // 5 packages -> MPS
        when(splitter.splitAndEnqueueForOrder(eq(500L), eq(5), eq("ACME")))
                .thenReturn(new UspsLabelQueueService.EnqueueMpsResult(
                        500L, 5,
                        LocalDateTime.of(2026, 9, 16, 12, 0),
                        LocalDateTime.of(2026, 9, 16, 12, 6)));

        seedJob(200L, "500", 1);
        service.runJob(200L);

        BulkLabelJob terminal = saved.get(200L);
        assertEquals("COMPLETED", terminal.getStatus());
        assertEquals(1, terminal.getSuccessfulCount(),
                "MPS-queued outcome counts as success (one order, N pieces)");
        assertEquals(0, terminal.getFailedCount());
        assertTrue(terminal.getFailureMessage().contains("MPS split into 5 pieces"),
                "Summary should mention the piece count; got: " + terminal.getFailureMessage());
        assertTrue(terminal.getFailureMessage().contains("mps-progress/500"),
                "Summary should include the mps-progress endpoint hint");
        assertTrue(terminal.getFailureDetailsJson().contains("USPS_QUEUED_MPS"),
                "Structured details should carry the MPS code");
        verify(splitter, times(1)).splitAndEnqueueForOrder(eq(500L), eq(5), eq("ACME"));
        // Single-label enqueue MUST NOT fire.
        verify(queue, never()).enqueue(any());
        verify(carrierService, never()).generateLabel(anyLong(), any(), anyString(), any());
    }

    @Test
    void singlePackageOrderStaysOnPrF1Path() {
        stubProvider("USPS_DIRECT");
        stubOrder(600, "USPS", "ACME", 1);   // single package
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class)))
                .thenReturn(new UspsLabelQueueService.EnqueueResult(
                        888L, LocalDateTime.of(2026, 9, 16, 12, 5)));

        seedJob(201L, "600", 1);
        service.runJob(201L);

        BulkLabelJob terminal = saved.get(201L);
        assertEquals("COMPLETED", terminal.getStatus());
        assertEquals(1, terminal.getSuccessfulCount());
        // PR-F1 wording preserved for single-label enqueue.
        assertTrue(terminal.getFailureMessage().contains("queued to USPS Direct queue"));
        assertTrue(terminal.getFailureMessage().contains("888"));
        verify(queue, times(1)).enqueue(any());
        verify(splitter, never()).splitAndEnqueueForOrder(anyLong(), anyInt(), anyString());
    }

    @Test
    void nullPackageCountStaysOnPrF1Path() {
        stubProvider("USPS_DIRECT");
        stubOrder(700, "USPS", "ACME", null);   // packageCount unknown
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class)))
                .thenReturn(new UspsLabelQueueService.EnqueueResult(
                        777L, LocalDateTime.of(2026, 9, 16, 12, 5)));

        seedJob(202L, "700", 1);
        service.runJob(202L);

        assertEquals("COMPLETED", saved.get(202L).getStatus());
        assertEquals(1, saved.get(202L).getSuccessfulCount());
        verify(queue, times(1)).enqueue(any());
        verify(splitter, never()).splitAndEnqueueForOrder(anyLong(), anyInt(), anyString());
    }

    @Test
    void splitterUnwiredFallsBackToSingleEnqueue() {
        // Rebuild without the splitter.
        BulkLabelServiceImpl noSplitter = new BulkLabelServiceImpl(
                jobRepo, carrierService, orderRepo,
                new TenantScopeEnforcer(new AccessScopePolicy(false)));
        noSplitter.setUspsLabelQueueService(queue);
        noSplitter.setSystemSettingService(settings);

        stubProvider("USPS_DIRECT");
        stubOrder(800, "USPS", "ACME", 5);   // would-be MPS
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class)))
                .thenReturn(new UspsLabelQueueService.EnqueueResult(
                        1111L, LocalDateTime.of(2026, 9, 16, 12, 5)));

        seedJob(203L, "800", 1);
        noSplitter.runJob(203L);

        assertEquals("COMPLETED", saved.get(203L).getStatus());
        assertEquals(1, saved.get(203L).getSuccessfulCount());
        // Single-label enqueue took over.
        verify(queue, times(1)).enqueue(any());
        // Splitter mock never touched (it wasn't wired).
        verifyNoInteractions(splitter);
    }

    @Test
    void splitterFailureFallsBackToSyncPath() {
        stubProvider("USPS_DIRECT");
        stubOrder(900, "USPS", "ACME", 5);
        when(splitter.splitAndEnqueueForOrder(anyLong(), anyInt(), anyString()))
                .thenThrow(new IllegalStateException("splitter down"));
        // Sync fallback needs the carrier stub for the label call.
        when(carrierService.generateLabel(eq(900L), any(), anyString(), any()))
                .thenReturn(com.multiship.backend.dto.ApiResponse
                        .<com.multiship.backend.dto.LabelGenerationResponse>builder()
                        .status("success").code(200).message("ok")
                        .data(com.multiship.backend.dto.LabelGenerationResponse.builder()
                                .orderNo(900L)
                                .trackingNumber("TN-900")
                                .labelPdf(java.util.Base64.getEncoder().encodeToString("PDF".getBytes()))
                                .status("GENERATED")
                                .build())
                        .build());

        seedJob(204L, "900", 1);
        service.runJob(204L);

        BulkLabelJob terminal = saved.get(204L);
        assertEquals("COMPLETED", terminal.getStatus());
        assertEquals(1, terminal.getSuccessfulCount(),
                "Splitter failure -> sync path lands the label");
        verify(splitter, times(1)).splitAndEnqueueForOrder(anyLong(), anyInt(), anyString());
        // Sync connector was called for the fallback.
        verify(carrierService, times(1)).generateLabel(eq(900L), any(), anyString(), any());
        // Single-label enqueue MUST NOT be called on the splitter-failure
        // path (MPS branch already committed to the MPS route; failure
        // goes straight to sync, not to PR-F1 single-label as a middle
        // stop). This keeps operator UX predictable: MPS orders either
        // batch-queue or hit the connector, never end up as one PR-F1
        // row for a 5-piece order.
        verify(queue, never()).enqueue(any());
    }
}
