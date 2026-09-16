package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.UspsLabelQueueMetricsDTO;
import com.multiship.backend.dto.UspsMpsProgressDTO;
import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.model.UspsLabelQueueItem.Status;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import com.multiship.backend.repository.UspsLabelQueueRepository.StatusCount;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR-F2 - dedicated pure-Mockito tests for the
 * {@code /mps-progress/{orderNo}} endpoint added in
 * {@link UspsLabelQueueAdminController}. Follows the same style as
 * {@link UspsLabelQueueAdminControllerTest} - invoke the controller
 * method directly and assert the {@link ResponseEntity} envelope.
 *
 * <p>The class-level {@code @PreAuthorize("hasRole('ADMIN')")} + the
 * method-level {@code hasRole('ADMIN') or hasRole('USER')} override
 * are Spring Security concerns tested end-to-end elsewhere; these
 * tests pin the DTO shape + aggregation math + 404 wording.
 */
class UspsLabelQueueAdminControllerMpsTest {

    private UspsLabelQueueService service;
    private UspsLabelQueueRepository repository;
    private UspsLabelQueueAdminController controller;

    @BeforeEach
    void setUp() {
        service = mock(UspsLabelQueueService.class);
        repository = mock(UspsLabelQueueRepository.class);
        controller = new UspsLabelQueueAdminController(service, repository);
    }

    // ================================================================
    // Happy path: all QUEUED
    // ================================================================

    @Test
    void mpsProgress_allQueued_returns200WithZeroDoneAndNonNullEta() {
        long orderNo = 12345L;
        when(repository.findStatusCountsByParentOrderNo(orderNo))
                .thenReturn(List.of(new StatusCount(Status.QUEUED, 1000L)));
        when(repository.findByParentOrderNoOrderBySequenceNumberAsc(orderNo))
                .thenReturn(nQueuedPieces(1000));
        // Metrics used to project the completion ETA - only cap + start
        // matter for the calculation.
        when(service.getMetrics()).thenReturn(metricsWithCap(55L));

        ResponseEntity<ApiResponse<UspsMpsProgressDTO>> resp = controller.mpsProgress(orderNo);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        UspsMpsProgressDTO d = resp.getBody().getData();
        assertNotNull(d);
        assertEquals(orderNo, d.getParentOrderNo());
        assertEquals(1000L, d.getTotalPieces());
        assertEquals(1000L, d.getByStatus().get("QUEUED"));
        assertFalse(d.getByStatus().containsKey("DONE"), "DONE absent when zero");
        assertEquals(0, BigDecimal.ZERO.compareTo(d.getPercentComplete()),
                "0/1000 done -> 0.0%");
        assertNotNull(d.getEstimatedCompletionAt(),
                "Partially-drained batch (1000 remaining) has projected ETA");
        assertNull(d.getStartedAt(),
                "Nothing started yet -> earliestStarted null");
        assertNotNull(d.getTrackingNumbers());
        assertTrue(d.getTrackingNumbers().isEmpty(),
                "Nothing done yet -> tracking preview empty");
    }

    // ================================================================
    // Partial: some DONE + some PROCESSING
    // ================================================================

    @Test
    void mpsProgress_partial_returns200WithMixedByStatusAndPercent() {
        long orderNo = 12345L;
        when(repository.findStatusCountsByParentOrderNo(orderNo))
                .thenReturn(List.of(
                        new StatusCount(Status.QUEUED, 750L),
                        new StatusCount(Status.PROCESSING, 5L),
                        new StatusCount(Status.DONE, 240L),
                        new StatusCount(Status.FAILED, 3L),
                        new StatusCount(Status.CANCELLED, 2L)));
        // Build 1000 pieces: 240 DONE with tracking numbers, 5 processing,
        // 750 queued, 3 failed, 2 cancelled. Seq order matters for the
        // tracking preview.
        LocalDateTime start = LocalDateTime.of(2026, 9, 16, 8, 0);
        List<UspsLabelQueueItem> pieces = new java.util.ArrayList<>(1000);
        for (int i = 1; i <= 240; i++) {
            pieces.add(donePiece(i, start.plusMinutes(i), start.plusMinutes(i).plusSeconds(3),
                    "9400111899560000000" + String.format("%04d", i)));
        }
        for (int i = 241; i <= 245; i++) {
            pieces.add(processingPiece(i, start.plusMinutes(i)));
        }
        for (int i = 246; i <= 995; i++) {
            pieces.add(queuedPiece(i));
        }
        for (int i = 996; i <= 998; i++) {
            pieces.add(failedPiece(i, start.plusMinutes(i), start.plusMinutes(i).plusSeconds(2)));
        }
        for (int i = 999; i <= 1000; i++) {
            pieces.add(cancelledPiece(i, start.plusMinutes(i), start.plusMinutes(i).plusSeconds(1)));
        }
        when(repository.findByParentOrderNoOrderBySequenceNumberAsc(orderNo)).thenReturn(pieces);
        when(service.getMetrics()).thenReturn(metricsWithCap(55L));

        ResponseEntity<ApiResponse<UspsMpsProgressDTO>> resp = controller.mpsProgress(orderNo);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        UspsMpsProgressDTO d = resp.getBody().getData();
        assertEquals(1000L, d.getTotalPieces());
        assertEquals(750L, d.getByStatus().get("QUEUED"));
        assertEquals(5L,   d.getByStatus().get("PROCESSING"));
        assertEquals(240L, d.getByStatus().get("DONE"));
        assertEquals(3L,   d.getByStatus().get("FAILED"));
        assertEquals(2L,   d.getByStatus().get("CANCELLED"));
        // 240 / 1000 * 100 = 24.0
        assertEquals(0, new BigDecimal("24.0").compareTo(d.getPercentComplete()),
                "240/1000 done -> 24.0%");
        // Preview capped at TRACKING_NUMBER_PREVIEW_LIMIT (20), in
        // sequence order.
        assertEquals(UspsMpsProgressDTO.TRACKING_NUMBER_PREVIEW_LIMIT,
                d.getTrackingNumbers().size(),
                "Tracking preview should be capped at 20 even with 240 available");
        assertEquals("94001118995600000000001", d.getTrackingNumbers().get(0));
        assertNotNull(d.getStartedAt(), "earliestStarted should be set");
        assertEquals(start.plusMinutes(1), d.getStartedAt(),
                "earliestStarted must be the min startedAt across all pieces");
        assertNotNull(d.getEstimatedCompletionAt(),
                "Partial batch (750 QUEUED + 5 PROCESSING) has projected ETA");
    }

    // ================================================================
    // Fully drained: all DONE
    // ================================================================

    @Test
    void mpsProgress_allDone_returns200WithHundredPercentAndActualCompletion() {
        long orderNo = 12345L;
        when(repository.findStatusCountsByParentOrderNo(orderNo))
                .thenReturn(List.of(new StatusCount(Status.DONE, 10L)));
        LocalDateTime start = LocalDateTime.of(2026, 9, 16, 8, 0);
        LocalDateTime lastCompleted = start.plusMinutes(9).plusSeconds(5);
        List<UspsLabelQueueItem> pieces = new java.util.ArrayList<>(10);
        for (int i = 1; i <= 10; i++) {
            pieces.add(donePiece(i, start.plusMinutes(i - 1),
                    start.plusMinutes(i - 1).plusSeconds(5),
                    "TN-" + i));
        }
        when(repository.findByParentOrderNoOrderBySequenceNumberAsc(orderNo)).thenReturn(pieces);

        ResponseEntity<ApiResponse<UspsMpsProgressDTO>> resp = controller.mpsProgress(orderNo);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        UspsMpsProgressDTO d = resp.getBody().getData();
        assertEquals(10L, d.getTotalPieces());
        assertEquals(10L, d.getByStatus().get("DONE"));
        assertEquals(0, new BigDecimal("100.0").compareTo(d.getPercentComplete()),
                "10/10 done -> 100.0%");
        // Fully drained -> estimatedCompletionAt is the actual latestCompleted.
        assertEquals(lastCompleted, d.getEstimatedCompletionAt(),
                "Fully drained batch: ETA is the real latest completedAt");
        // All 10 tracking numbers fit under the 20-cap.
        assertEquals(10, d.getTrackingNumbers().size());
        assertEquals("TN-1", d.getTrackingNumbers().get(0));
        assertEquals("TN-10", d.getTrackingNumbers().get(9));
    }

    // ================================================================
    // 404: no MPS rows for the order
    // ================================================================

    @Test
    void mpsProgress_noRowsForOrder_returns404() {
        long orderNo = 99999L;
        when(repository.findStatusCountsByParentOrderNo(orderNo))
                .thenReturn(List.of());

        ResponseEntity<ApiResponse<UspsMpsProgressDTO>> resp = controller.mpsProgress(orderNo);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getBody().getErrorCode());
        assertTrue(resp.getBody().getMessage().contains(String.valueOf(orderNo)),
                "404 message should name the offending orderNo");
    }

    @Test
    void mpsProgress_nullOrderNo_returns404() {
        ResponseEntity<ApiResponse<UspsMpsProgressDTO>> resp = controller.mpsProgress(null);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ================================================================
    // helpers
    // ================================================================

    private static UspsLabelQueueMetricsDTO metricsWithCap(long cap) {
        return UspsLabelQueueMetricsDTO.builder()
                .configuredHourlyCap(cap)
                .estimatedStartAt(LocalDateTime.of(2026, 9, 16, 12, 0))
                .build();
    }

    private static List<UspsLabelQueueItem> nQueuedPieces(int n) {
        List<UspsLabelQueueItem> pieces = new java.util.ArrayList<>(n);
        for (int i = 1; i <= n; i++) pieces.add(queuedPiece(i));
        return pieces;
    }

    private static UspsLabelQueueItem queuedPiece(int seq) {
        return UspsLabelQueueItem.builder()
                .id((long) seq)
                .tenantCode("ACME")
                .shipmentId((long) -seq)
                .priority(100)
                .status(Status.QUEUED)
                .retryCount(0)
                .parentOrderNo(12345L)
                .sequenceNumber(seq)
                .enqueuedAt(LocalDateTime.of(2026, 9, 16, 7, 0))
                .build();
    }

    private static UspsLabelQueueItem processingPiece(int seq, LocalDateTime startedAt) {
        return UspsLabelQueueItem.builder()
                .id((long) seq)
                .tenantCode("ACME")
                .shipmentId((long) -seq)
                .priority(100)
                .status(Status.PROCESSING)
                .retryCount(0)
                .parentOrderNo(12345L)
                .sequenceNumber(seq)
                .enqueuedAt(LocalDateTime.of(2026, 9, 16, 7, 0))
                .startedAt(startedAt)
                .build();
    }

    private static UspsLabelQueueItem donePiece(int seq, LocalDateTime startedAt,
            LocalDateTime completedAt, String tracking) {
        return UspsLabelQueueItem.builder()
                .id((long) seq)
                .tenantCode("ACME")
                .shipmentId((long) -seq)
                .priority(100)
                .status(Status.DONE)
                .retryCount(0)
                .parentOrderNo(12345L)
                .sequenceNumber(seq)
                .enqueuedAt(LocalDateTime.of(2026, 9, 16, 7, 0))
                .startedAt(startedAt)
                .completedAt(completedAt)
                .trackingNumber(tracking)
                .build();
    }

    private static UspsLabelQueueItem failedPiece(int seq, LocalDateTime startedAt,
            LocalDateTime completedAt) {
        return UspsLabelQueueItem.builder()
                .id((long) seq)
                .tenantCode("ACME")
                .shipmentId((long) -seq)
                .priority(100)
                .status(Status.FAILED)
                .retryCount(1)
                .lastError("test-failure")
                .parentOrderNo(12345L)
                .sequenceNumber(seq)
                .enqueuedAt(LocalDateTime.of(2026, 9, 16, 7, 0))
                .startedAt(startedAt)
                .completedAt(completedAt)
                .build();
    }

    private static UspsLabelQueueItem cancelledPiece(int seq, LocalDateTime startedAt,
            LocalDateTime completedAt) {
        return UspsLabelQueueItem.builder()
                .id((long) seq)
                .tenantCode("ACME")
                .shipmentId((long) -seq)
                .priority(100)
                .status(Status.CANCELLED)
                .retryCount(0)
                .parentOrderNo(12345L)
                .sequenceNumber(seq)
                .enqueuedAt(LocalDateTime.of(2026, 9, 16, 7, 0))
                .startedAt(startedAt)
                .completedAt(completedAt)
                .build();
    }
}
