package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.model.BulkLabelJob;
import com.multiship.backend.model.Order;
import com.multiship.backend.repository.BulkLabelJobRepository;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
 * PR-F1 Agent-2 — unit tests for the queue-routing branch added to
 * {@link BulkLabelServiceImpl#processOneOrder}. Mirrors the pure-Mockito
 * style used by {@code BulkLabelServiceImplTest} — no Spring context,
 * no DB, {@code runJob} invoked synchronously so assertions land
 * without racing the dispatch executor.
 *
 * <p>Matrix (from the F1 brief):
 * <table><thead><tr><th>USPS_PROVIDER</th><th>Order carrier</th>
 * <th>Expected</th></tr></thead><tbody>
 * <tr><td>STAMPS_COM</td><td>USPS</td><td>sync (no enqueue)</td></tr>
 * <tr><td>USPS_DIRECT</td><td>USPS</td><td>enqueue</td></tr>
 * <tr><td>USPS_DIRECT</td><td>FEDEX</td><td>sync (queue not used)</td></tr>
 * <tr><td>USPS_DIRECT</td><td>USPS + enqueue throws</td>
 *     <td>sync fallback (order still tries to ship)</td></tr>
 * </tbody></table>
 */
class BulkLabelServiceImplUspsQueueTest {

    private BulkLabelJobRepository jobRepo;
    private CarrierService carrierService;
    private OrderRepository orderRepo;
    private SystemSettingService settings;
    private UspsLabelQueueService queue;
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

        // Simulate JPA save + findById against an in-memory Map.
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
        // Inject the two optional dependencies added in PR-F1.
        service.setUspsLabelQueueService(queue);
        service.setSystemSettingService(settings);
    }

    /* -------------------------- helpers -------------------------- */

    private void stubProvider(String value) {
        when(settings.getDecrypted("USPS_PROVIDER")).thenReturn(Optional.ofNullable(value));
    }

    /** Stub the order lookup with a specific ship-via code. */
    private void stubOrder(int orderNo, String shipviaCd, String tenantId) {
        Order o = new Order();
        o.setOrderNo(orderNo);
        o.setShipviaCd(shipviaCd);
        o.setTenantId(tenantId);
        when(orderRepo.findByOrderNo(orderNo)).thenReturn(Optional.of(o));
    }

    private void stubGenerate(long orderNo, LabelGenerationResponse label) {
        ApiResponse<LabelGenerationResponse> wrapped = ApiResponse
                .<LabelGenerationResponse>builder()
                .status("success").code(200).message("ok").data(label).build();
        when(carrierService.generateLabel(eq(orderNo), any(), anyString(), any()))
                .thenReturn(wrapped);
    }

    private static LabelGenerationResponse okLabel(long orderNo) {
        return LabelGenerationResponse.builder()
                .orderNo(orderNo)
                .trackingNumber("TN-" + orderNo)
                .labelPdf(java.util.Base64.getEncoder().encodeToString(
                        ("PDF-CONTENT-" + orderNo).getBytes()))
                .status("GENERATED")
                .build();
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

    /** Count PDF entries inside a base64-encoded ZIP payload. Queued
     *  outcomes have NO ZIP entry (label bytes land later when the queue
     *  processor runs); sync outcomes have one PDF per successful order. */
    private static int pdfEntriesIn(String base64Zip) throws Exception {
        if (base64Zip == null) return 0;
        byte[] bytes = java.util.Base64.getDecoder().decode(base64Zip);
        int count = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().endsWith(".pdf")) count++;
            }
        }
        return count;
    }

    /* -------------------------- Matrix cases -------------------------- */

    /**
     * Row 1 — USPS_PROVIDER=STAMPS_COM. Every carrier stays on the sync
     * path (Stamps.com has no 60/hr platform cap; queueing there would
     * waste headroom). No enqueue call should fire.
     */
    @Test
    void stampsProviderKeepsUspsOrdersSync() {
        stubProvider("STAMPS_COM");
        stubOrder(1, "L01", "ACME");   // legacy USPS code
        stubGenerate(1L, okLabel(1L));

        seedJob(100L, "1", 1);
        service.runJob(100L);

        // Sync path landed the label — successful count incremented,
        // NO queue interaction happened.
        BulkLabelJob terminal = saved.get(100L);
        assertEquals("COMPLETED", terminal.getStatus());
        assertEquals(1, terminal.getSuccessfulCount());
        assertEquals(0, terminal.getFailedCount());
        verifyNoInteractions(queue);
        verify(carrierService, times(1)).generateLabel(eq(1L), any(), anyString(), any());
    }

    /**
     * Row 2 — USPS_PROVIDER=USPS_DIRECT + USPS shipment. Order routes
     * through the persistent queue: enqueue is called once, the sync
     * connector call is skipped, and the outcome counts as success
     * (label WILL be printed once the queue drains). No PDF ends up in
     * the ZIP because the label bytes come later.
     */
    @Test
    void uspsDirectProviderEnqueuesUspsShipment() throws Exception {
        stubProvider("USPS_DIRECT");
        stubOrder(42, "USPS", "ACME");
        // A fresh queue item id + estimated start comes back from Agent-1.
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class)))
                .thenReturn(new UspsLabelQueueService.EnqueueResult(
                        999L, LocalDateTime.of(2026, 9, 16, 12, 0)));

        seedJob(101L, "42", 1);
        service.runJob(101L);

        BulkLabelJob terminal = saved.get(101L);
        assertEquals("COMPLETED", terminal.getStatus());
        assertEquals(1, terminal.getSuccessfulCount(),
                "Queued items count as success — label WILL print when the queue drains");
        assertEquals(0, terminal.getFailedCount());
        // The failure/summary message names the queue routing so
        // operators can find the item.
        assertTrue(terminal.getFailureMessage().contains("queued to USPS Direct queue"),
                "Summary should record the queue routing; got: " + terminal.getFailureMessage());
        assertTrue(terminal.getFailureMessage().contains("999"),
                "Summary should include the queue item id");
        // Structured details should carry the machine-readable code.
        assertTrue(terminal.getFailureDetailsJson().contains("USPS_QUEUED"),
                "Failure details JSON should include the USPS_QUEUED code");
        verify(queue, times(1)).enqueue(any(UspsLabelQueueService.EnqueueRequest.class));
        // Sync connector NEVER called — the whole point of the queue.
        verify(carrierService, never()).generateLabel(anyLong(), any(), anyString(), any());
        // Queue-only batch — ZIP has NO PDF entries (label bytes land
        // later when the queue processor runs). The wrapper may still
        // exist with just an empty central directory; assert on PDF
        // entry count rather than the raw bytes so this stays robust
        // if the writer changes.
        assertEquals(0, pdfEntriesIn(terminal.getResultZipBase64()),
                "No PDF should be in the ZIP for a queue-only batch");
    }

    /**
     * Row 3 — USPS_PROVIDER=USPS_DIRECT but the order's carrier is
     * FedEx. FedEx has no USPS cap; must stay on the sync path even
     * with the toggle flipped. No enqueue call.
     */
    @Test
    void uspsDirectProviderLeavesNonUspsOrdersSync() {
        stubProvider("USPS_DIRECT");
        stubOrder(7, "FEDEX_GROUND", "ACME");
        stubGenerate(7L, okLabel(7L));

        seedJob(102L, "7", 1);
        service.runJob(102L);

        BulkLabelJob terminal = saved.get(102L);
        assertEquals("COMPLETED", terminal.getStatus());
        assertEquals(1, terminal.getSuccessfulCount());
        // FedEx sync path — connector called, queue untouched.
        verify(carrierService, times(1)).generateLabel(eq(7L), any(), anyString(), any());
        verifyNoInteractions(queue);
    }

    /**
     * Row 4 — enqueue itself throws (Agent-1's queue is momentarily
     * unreachable / full). The batch must not go red; the order falls
     * back to the sync path so at least it tries to ship. This keeps
     * queue outages from bricking the bulk-label UI.
     */
    @Test
    void enqueueFailureFallsBackToSyncPath() {
        stubProvider("USPS_DIRECT");
        stubOrder(88, "L01", "ACME");
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class)))
                .thenThrow(new RuntimeException("queue overflowed"));
        stubGenerate(88L, okLabel(88L));

        seedJob(103L, "88", 1);
        service.runJob(103L);

        BulkLabelJob terminal = saved.get(103L);
        assertEquals("COMPLETED", terminal.getStatus());
        // Sync fallback landed the label — success count incremented.
        assertEquals(1, terminal.getSuccessfulCount());
        assertEquals(0, terminal.getFailedCount());
        verify(queue, times(1)).enqueue(any(UspsLabelQueueService.EnqueueRequest.class));
        verify(carrierService, times(1)).generateLabel(eq(88L), any(), anyString(), any());
    }

    /**
     * PROVISIONING_USPS_DIRECT is a transitional state — the runtime
     * connector is still {@code StampsConnector} until the operator
     * flips fully to USPS_DIRECT. Queue must stay off; matches
     * {@code CarrierServiceImpl.resolveUspsProvider}'s dispatch table.
     */
    @Test
    void provisioningProviderKeepsSyncPath() {
        stubProvider("PROVISIONING_USPS_DIRECT");
        stubOrder(11, "USPS", "ACME");
        stubGenerate(11L, okLabel(11L));

        seedJob(104L, "11", 1);
        service.runJob(104L);

        assertEquals("COMPLETED", saved.get(104L).getStatus());
        assertEquals(1, saved.get(104L).getSuccessfulCount());
        verifyNoInteractions(queue);
    }

    /**
     * Mixed batch — one USPS order (queued) + one FedEx order (sync).
     * Both count as success but only the FedEx label ends up in the
     * ZIP; the USPS entry goes into the summary/details.
     */
    @Test
    void mixedBatchQueuesUspsAndSyncsFedex() throws Exception {
        stubProvider("USPS_DIRECT");
        stubOrder(1, "USPS", "ACME");
        stubOrder(2, "FEDEX_GROUND", "ACME");
        when(queue.enqueue(any(UspsLabelQueueService.EnqueueRequest.class)))
                .thenReturn(new UspsLabelQueueService.EnqueueResult(
                        555L, LocalDateTime.of(2026, 9, 16, 12, 5)));
        stubGenerate(2L, okLabel(2L));

        seedJob(105L, "1,2", 2);
        service.runJob(105L);

        BulkLabelJob terminal = saved.get(105L);
        assertEquals("COMPLETED", terminal.getStatus());
        assertEquals(2, terminal.getSuccessfulCount(),
                "Both queued (USPS) and sync (FedEx) count as success");
        assertEquals(0, terminal.getFailedCount());
        verify(queue, times(1)).enqueue(any(UspsLabelQueueService.EnqueueRequest.class));
        verify(carrierService, times(1)).generateLabel(eq(2L), any(), anyString(), any());
        verify(carrierService, never()).generateLabel(eq(1L), any(), anyString(), any());
        // Exactly ONE PDF (the FedEx one) — USPS 1 went to the queue
        // so its bytes aren't in the ZIP.
        assertEquals(1, pdfEntriesIn(terminal.getResultZipBase64()),
                "Mixed batch: only the sync FedEx label should be in the ZIP");
    }

    /**
     * When the queue service isn't wired at all (pure-Mockito 4-arg
     * constructor — the legacy tests + this test's baseline), routing
     * short-circuits. Provider setting doesn't even get read.
     */
    @Test
    void withoutQueueServiceRoutingIsBypassed() {
        // Rebuild the service with the 4-arg constructor (no setters).
        BulkLabelServiceImpl bareService = new BulkLabelServiceImpl(
                jobRepo, carrierService, orderRepo,
                new TenantScopeEnforcer(new AccessScopePolicy(false)));
        stubProvider("USPS_DIRECT");
        stubOrder(1, "USPS", "ACME");
        stubGenerate(1L, okLabel(1L));

        seedJob(106L, "1", 1);
        bareService.runJob(106L);

        assertEquals("COMPLETED", saved.get(106L).getStatus());
        assertEquals(1, saved.get(106L).getSuccessfulCount());
        // Queue is untouched because it was never injected.
        verifyNoInteractions(queue);
        // Sync path — connector called.
        verify(carrierService, times(1)).generateLabel(eq(1L), any(), anyString(), any());
    }
}
