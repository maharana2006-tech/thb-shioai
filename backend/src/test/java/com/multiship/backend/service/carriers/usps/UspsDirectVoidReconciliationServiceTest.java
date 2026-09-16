package com.multiship.backend.service.carriers.usps;

import com.multiship.backend.dto.UspsVoidReconciliationSummaryDTO;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.OrderTrackingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-D — {@link UspsDirectVoidReconciliationService} pure-Mockito tests.
 *
 * <p>The service parses an eVS Refund report CSV and applies per-row
 * transitions to matching {@link OrderTracking} rows:
 * <ul>
 *   <li>APPROVED → status stays VOIDED, reconciliation stamped APPROVED</li>
 *   <li>DENIED → status flips to VOID_FAILED, reconciliation stamped DENIED</li>
 *   <li>PENDING → nothing changes (retried next batch)</li>
 * </ul>
 * A per-tracking in-memory store lets the tests observe the state
 * changes without a real database.
 */
class UspsDirectVoidReconciliationServiceTest {

    private OrderTrackingRepository repo;
    private UspsDirectVoidReconciliationService service;
    /** Shared in-memory tracking DB the mock repo reads/writes. */
    private Map<String, OrderTracking> store;

    @BeforeEach
    void setUp() {
        repo = mock(OrderTrackingRepository.class);
        service = new UspsDirectVoidReconciliationService(repo);
        store = new HashMap<>();

        // Case-insensitive lookup mirrors the real repo.
        when(repo.findByTrackingNumberIgnoreCase(anyString()))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0);
                    return Optional.ofNullable(store.get(key.toLowerCase()));
                });
        // save() returns the same instance — tests mutate the entity
        // by reference before this call so no extra copy is needed.
        when(repo.save(any(OrderTracking.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private OrderTracking seed(String trackingNumber, String status) {
        OrderTracking t = new OrderTracking();
        t.setTrackingNumber(trackingNumber);
        t.setStatus(status);
        t.setShipViaCd("USPS");
        t.setOrderNo(1000 + store.size());
        store.put(trackingNumber.toLowerCase(), t);
        return t;
    }

    private static InputStream csv(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    // ================================================================
    // Empty inputs
    // ================================================================

    @Test
    void nullInputReturnsEmptySummary() {
        UspsVoidReconciliationSummaryDTO s = service.reconcile(null);
        assertEquals(0, s.processedRows());
    }

    @Test
    void emptyStreamReturnsEmptySummary() {
        UspsVoidReconciliationSummaryDTO s = service.reconcile(csv(""));
        assertEquals(0, s.processedRows());
    }

    @Test
    void headerOnlyReturnsZeroProcessedRows() throws Exception {
        String fixture = loadFixture("usps/v3/reconciliation/evs_refund_report_empty.csv");
        UspsVoidReconciliationSummaryDTO s = service.reconcile(csv(fixture));
        assertEquals(0, s.processedRows());
        assertEquals(0, s.reconciledApproved());
        assertEquals(0, s.reconciledDenied());
    }

    // ================================================================
    // Mixed CSV — happy path per-row transitions
    // ================================================================

    @Test
    void mixedCsvTransitionsRowsPerStatus() throws Exception {
        OrderTracking approved = seed("9400111899223197428301", "VOIDED");
        OrderTracking denied = seed("9400111899223197428318", "VOIDED");
        OrderTracking pending = seed("9400111899223197428325", "VOIDED");

        String fixture = loadFixture("usps/v3/reconciliation/evs_refund_report_mixed.csv");
        UspsVoidReconciliationSummaryDTO s = service.reconcile(csv(fixture));

        assertEquals(3, s.processedRows());
        assertEquals(1, s.reconciledApproved());
        assertEquals(1, s.reconciledDenied());
        assertEquals(1, s.pending());
        assertEquals(0, s.skipped());
        assertTrue(s.errors().isEmpty(),
                "no rows should produce errors on a well-formed fixture; got: " + s.errors());

        // APPROVED: stays VOIDED, reconciliation stamped APPROVED.
        assertEquals("VOIDED", approved.getStatus());
        assertEquals(UspsDirectVoidReconciliationService.RECONCILED_APPROVED,
                approved.getVoidReconciliationStatus());
        assertNotNull(approved.getVoidReconciliationCheckedAt());

        // DENIED: flips to VOID_FAILED, reconciliation stamped DENIED.
        assertEquals(UspsDirectVoidReconciliationService.STATUS_VOID_FAILED, denied.getStatus());
        assertEquals(UspsDirectVoidReconciliationService.RECONCILED_DENIED,
                denied.getVoidReconciliationStatus());
        assertNotNull(denied.getVoidReconciliationCheckedAt());

        // PENDING: nothing changes.
        assertEquals("VOIDED", pending.getStatus());
        assertNull(pending.getVoidReconciliationStatus());
        assertNull(pending.getVoidReconciliationCheckedAt());
    }

    // ================================================================
    // Missing tracking → skipped
    // ================================================================

    @Test
    void unknownTrackingNumberIsSkipped() {
        String body = "TrackingNumber,RefundStatus\n"
                + "UNKNOWN-999,APPROVED\n";
        UspsVoidReconciliationSummaryDTO s = service.reconcile(csv(body));
        assertEquals(1, s.processedRows());
        assertEquals(0, s.reconciledApproved());
        assertEquals(1, s.skipped());
    }

    @Test
    void trackingInDifferentLocalStatusIsSkipped() {
        // Row exists but local status isn't VOIDED — the reconciler
        // must not drag it into VOID_FAILED / mark it APPROVED.
        OrderTracking t = seed("TRK-100", "GENERATED");
        String body = "TrackingNumber,RefundStatus\nTRK-100,DENIED\n";
        UspsVoidReconciliationSummaryDTO s = service.reconcile(csv(body));
        assertEquals(1, s.processedRows());
        assertEquals(1, s.skipped());
        assertEquals("GENERATED", t.getStatus(),
                "non-VOIDED row must not be touched by reconciliation.");
        assertNull(t.getVoidReconciliationStatus());
    }

    // ================================================================
    // Malformed rows
    // ================================================================

    @Test
    void malformedHeaderThrowsIllegalArgumentException() throws Exception {
        String fixture = loadFixture("usps/v3/reconciliation/evs_refund_report_malformed.csv");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.reconcile(csv(fixture)));
        assertTrue(ex.getMessage().contains("TrackingNumber"),
                "message should name missing required column; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("RefundStatus"),
                "message should name missing required column; got: " + ex.getMessage());
    }

    @Test
    void malformedRowCountsAsErrorButContinues() {
        seed("TRK-200", "VOIDED");
        // First row is blank-ish (missing both required columns) — should
        // be flagged as an error. Second row is well-formed — should
        // reconcile normally.
        String body = "TrackingNumber,RefundStatus\n"
                + ",\n"
                + "TRK-200,APPROVED\n";
        UspsVoidReconciliationSummaryDTO s = service.reconcile(csv(body));
        assertEquals(2, s.processedRows());
        assertEquals(1, s.reconciledApproved());
        assertFalse(s.errors().isEmpty(), "malformed row should populate errors[]");
    }

    // ================================================================
    // Idempotency — running the same CSV twice
    // ================================================================

    @Test
    void secondPassOnSameCsvSkipsAlreadyReconciledRows() throws Exception {
        OrderTracking approved = seed("9400111899223197428301", "VOIDED");
        OrderTracking denied = seed("9400111899223197428318", "VOIDED");
        seed("9400111899223197428325", "VOIDED");

        String fixture = loadFixture("usps/v3/reconciliation/evs_refund_report_mixed.csv");
        // First pass — reconciles as above.
        service.reconcile(csv(fixture));
        // Second pass — same CSV, terminal statuses now block the writes.
        UspsVoidReconciliationSummaryDTO s2 = service.reconcile(csv(fixture));

        assertEquals(3, s2.processedRows());
        assertEquals(0, s2.reconciledApproved(),
                "second pass must not double-count APPROVED rows.");
        assertEquals(0, s2.reconciledDenied(),
                "second pass must not double-count DENIED rows.");
        // APPROVED + DENIED are terminal → both skipped; PENDING is not
        // terminal so it stays in the pending bucket on the second pass.
        assertEquals(2, s2.skipped());
        assertEquals(1, s2.pending());

        // Underlying state on the tracking rows is unchanged.
        assertEquals("VOIDED", approved.getStatus());
        assertEquals(UspsDirectVoidReconciliationService.STATUS_VOID_FAILED, denied.getStatus());
    }

    // ================================================================
    // DENIED writes twice (status + reconciliation) — verify save called
    // ================================================================

    @Test
    void deniedRowInvokesSaveOnceAndLogsWarn() {
        seed("TRK-300", "VOIDED");
        String body = "TrackingNumber,RefundStatus\nTRK-300,DENIED\n";
        service.reconcile(csv(body));
        // save() called once because both status + reconciliation
        // changes are folded into the same entity mutation.
        verify(repo, times(1)).save(any(OrderTracking.class));
    }

    // ================================================================
    // Fixture helper
    // ================================================================

    private static String loadFixture(String resourcePath) throws java.io.IOException {
        try (InputStream in = Objects.requireNonNull(
                UspsDirectVoidReconciliationServiceTest.class.getClassLoader()
                        .getResourceAsStream(resourcePath),
                "fixture not on classpath: " + resourcePath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
