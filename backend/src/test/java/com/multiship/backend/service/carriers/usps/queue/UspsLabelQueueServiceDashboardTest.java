package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.dto.UspsDashboardMetricsDTO;
import com.multiship.backend.dto.UspsQuotaHeadroomDTO;
import com.multiship.backend.dto.UspsReconciliationRollupDTO;
import com.multiship.backend.dto.UspsRetryBucketDTO;
import com.multiship.backend.model.UspsLabelQueueItem.Status;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import com.multiship.backend.repository.UspsLabelQueueRepository.HourlyRetryBucket;
import com.multiship.backend.service.carriers.usps.UspsDirectVoidReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR-F4 - pure-Mockito tests for
 * {@link UspsLabelQueueServiceImpl#getDashboardMetrics(Duration, Duration)}
 * and {@link UspsLabelQueueServiceImpl#getRetryBuckets(Duration)}.
 *
 * <p>Focus: composition (all 4 sub-DTOs populated), fallback shapes
 * (null processor / null reconciliation service), retry-bucket
 * mapping (NULL-safe SUM/COUNT), and default-window handling.
 */
class UspsLabelQueueServiceDashboardTest {

    private UspsLabelQueueRepository repo;
    private UspsLabelQueueProcessor processor;
    private UspsDirectVoidReconciliationService reconciliation;
    private UspsLabelQueueServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(UspsLabelQueueRepository.class);
        processor = mock(UspsLabelQueueProcessor.class);
        reconciliation = mock(UspsDirectVoidReconciliationService.class);
        service = new UspsLabelQueueServiceImpl(repo, 55L, processor, reconciliation);

        // Default: empty queue, empty retry buckets, empty reconciliation.
        when(repo.countByStatus(any())).thenReturn(0L);
        when(repo.findByStatusAndCompletedAtAfter(any(), any())).thenReturn(List.of());
        when(repo.findByStatusOrderByPriorityAscEnqueuedAtAsc(any())).thenReturn(List.of());
        when(repo.findQueueDepthByTenant(any())).thenReturn(List.of());
        when(repo.findRetryCountsByHour(any())).thenReturn(List.of());

        when(processor.getRemainingHourlyQuota()).thenReturn(55);
        when(processor.getConfiguredHourlyCap()).thenReturn(55L);

        when(reconciliation.getRollup(any())).thenReturn(
                UspsReconciliationRollupDTO.builder()
                        .lookbackDays(30)
                        .voidedShipmentsInWindow(0L)
                        .reconciledApproved(0L).reconciledDenied(0L).notYetReconciled(0L)
                        .pendingRefundValue(BigDecimal.ZERO).currency("USD")
                        .build());
    }

    // ================================================================
    // getDashboardMetrics composition
    // ================================================================

    @Test
    void dashboard_composesAllFourSubDtos() {
        UspsDashboardMetricsDTO dto = service.getDashboardMetrics(
                Duration.ofHours(24), Duration.ofDays(30));

        assertNotNull(dto);
        assertNotNull(dto.getGeneratedAt());
        assertNotNull(dto.getQueue(), "queue sub-DTO must be populated");
        assertNotNull(dto.getQuota(), "quota sub-DTO must be populated");
        assertNotNull(dto.getRetries(), "retries sub-DTO must be populated");
        assertNotNull(dto.getReconciliation(), "reconciliation sub-DTO must be populated");
    }

    @Test
    void dashboard_populatesQuotaFromProcessor() {
        when(processor.getRemainingHourlyQuota()).thenReturn(42);
        when(processor.getConfiguredHourlyCap()).thenReturn(55L);

        UspsDashboardMetricsDTO dto = service.getDashboardMetrics(
                Duration.ofHours(24), Duration.ofDays(30));

        UspsQuotaHeadroomDTO q = dto.getQuota();
        assertEquals(55L, q.getHourlyCap());
        assertEquals(42, q.getRemainingTokens());
        // (55 - 42) / 55 * 100 = 23.6
        assertEquals(new BigDecimal("23.6"), q.getUtilizationPercent());
        assertEquals(0L, q.getNextReplenishInSeconds()); // tokens available -> 0
    }

    @Test
    void dashboard_emptyQueue_allZerosNoThrow() {
        // The @BeforeEach already sets all counts to 0. Just make sure
        // we return a fully populated DTO with all fields at their
        // sensible zero defaults.
        UspsDashboardMetricsDTO dto = service.getDashboardMetrics(
                Duration.ofHours(24), Duration.ofDays(30));

        assertEquals(0L, dto.getQueue().getQueuedDepth());
        assertEquals(0L, dto.getQueue().getProcessingCount());
        assertTrue(dto.getRetries().getBuckets() == null
                || dto.getRetries().getBuckets().isEmpty());
        assertEquals(0L, dto.getReconciliation().getVoidedShipmentsInWindow());
        assertEquals(BigDecimal.ZERO, dto.getReconciliation().getPendingRefundValue());
    }

    @Test
    void dashboard_nullLookbacks_defaultTo24hAnd30d() {
        UspsDashboardMetricsDTO dto = service.getDashboardMetrics(null, null);

        assertNotNull(dto);
        // The retry sub-DTO's hoursLookback is populated from the
        // normalised window: null -> 24h.
        assertEquals(24, dto.getRetries().getHoursLookback());
    }

    @Test
    void dashboard_negativeLookback_fallsBackToDefault() {
        UspsDashboardMetricsDTO dto = service.getDashboardMetrics(
                Duration.ofHours(-5), Duration.ofDays(-10));
        assertEquals(24, dto.getRetries().getHoursLookback());
    }

    @Test
    void dashboard_zeroLookback_fallsBackToDefault() {
        UspsDashboardMetricsDTO dto = service.getDashboardMetrics(
                Duration.ZERO, Duration.ZERO);
        assertEquals(24, dto.getRetries().getHoursLookback());
    }

    // ================================================================
    // getRetryBuckets query mapping
    // ================================================================

    @Test
    void retryBuckets_mapsProjectionRowsToBucketRecords() {
        LocalDateTime hour1 = LocalDateTime.parse("2026-09-15T15:00:00");
        LocalDateTime hour2 = LocalDateTime.parse("2026-09-15T16:00:00");
        when(repo.findRetryCountsByHour(any())).thenReturn(List.of(
                fakeBucket(hour1, 55L, 3L, 1L),
                fakeBucket(hour2, 55L, 8L, 2L)));

        UspsRetryBucketDTO out = service.getRetryBuckets(Duration.ofHours(24));

        assertEquals(24, out.getHoursLookback());
        assertNotNull(out.getBuckets());
        assertEquals(2, out.getBuckets().size());

        UspsRetryBucketDTO.Bucket b1 = out.getBuckets().get(0);
        assertEquals(hour1.toInstant(java.time.ZoneOffset.UTC), b1.hourStart());
        assertEquals(55L, b1.attempts());
        assertEquals(3L, b1.retries());
        assertEquals(1L, b1.failures());
    }

    @Test
    void retryBuckets_nullValuesFromDb_collapseToZero() {
        when(repo.findRetryCountsByHour(any())).thenReturn(List.of(
                fakeBucket(LocalDateTime.parse("2026-09-15T15:00:00"),
                        null, null, null)));

        UspsRetryBucketDTO out = service.getRetryBuckets(Duration.ofHours(1));

        assertEquals(1, out.getBuckets().size());
        UspsRetryBucketDTO.Bucket b = out.getBuckets().get(0);
        assertEquals(0L, b.attempts());
        assertEquals(0L, b.retries());
        assertEquals(0L, b.failures());
    }

    @Test
    void retryBuckets_emptyResult_returnsEmptyList() {
        when(repo.findRetryCountsByHour(any())).thenReturn(Collections.emptyList());

        UspsRetryBucketDTO out = service.getRetryBuckets(Duration.ofHours(24));

        assertNotNull(out.getBuckets());
        assertTrue(out.getBuckets().isEmpty());
    }

    // ================================================================
    // Fallbacks - null processor / null reconciliation
    // ================================================================

    @Test
    void dashboard_nullProcessor_quotaDegradesToZeroRemaining() {
        UspsLabelQueueServiceImpl fallbackService =
                new UspsLabelQueueServiceImpl(repo, 55L, null, reconciliation);

        UspsDashboardMetricsDTO dto = fallbackService.getDashboardMetrics(
                Duration.ofHours(24), Duration.ofDays(30));

        assertNotNull(dto.getQuota());
        assertEquals(0, dto.getQuota().getRemainingTokens());
        // 100% utilisation because remaining = 0 and cap = 55.
        assertEquals(new BigDecimal("100.0"), dto.getQuota().getUtilizationPercent());
        // Next replenish is computed since the bucket is drained.
        assertTrue(dto.getQuota().getNextReplenishInSeconds() > 0);
    }

    @Test
    void dashboard_nullReconciliation_returnsEmptyRollup() {
        UspsLabelQueueServiceImpl fallbackService =
                new UspsLabelQueueServiceImpl(repo, 55L, processor, null);

        UspsDashboardMetricsDTO dto = fallbackService.getDashboardMetrics(
                Duration.ofHours(24), Duration.ofDays(30));

        UspsReconciliationRollupDTO r = dto.getReconciliation();
        assertNotNull(r);
        assertEquals(0L, r.getVoidedShipmentsInWindow());
        assertEquals(0L, r.getReconciledApproved());
        assertEquals(0L, r.getReconciledDenied());
        assertEquals(0L, r.getNotYetReconciled());
        assertNull(r.getLastReconciliationAt());
        assertEquals(BigDecimal.ZERO, r.getPendingRefundValue());
        assertEquals("USD", r.getCurrency());
    }

    // ================================================================
    // Quota edge cases
    // ================================================================

    @Test
    void dashboard_quotaAllTokensAvailable_zeroUtilization() {
        when(processor.getRemainingHourlyQuota()).thenReturn(55);
        when(processor.getConfiguredHourlyCap()).thenReturn(55L);

        UspsDashboardMetricsDTO dto = service.getDashboardMetrics(
                Duration.ofHours(24), Duration.ofDays(30));

        assertEquals(new BigDecimal("0.0"), dto.getQuota().getUtilizationPercent());
        assertEquals(0L, dto.getQuota().getNextReplenishInSeconds());
    }

    @Test
    void dashboard_quotaFullyDrained_reportsNextReplenish() {
        when(processor.getRemainingHourlyQuota()).thenReturn(0);
        when(processor.getConfiguredHourlyCap()).thenReturn(55L);

        UspsDashboardMetricsDTO dto = service.getDashboardMetrics(
                Duration.ofHours(24), Duration.ofDays(30));

        assertEquals(new BigDecimal("100.0"), dto.getQuota().getUtilizationPercent());
        assertTrue(dto.getQuota().getNextReplenishInSeconds() > 0);
        assertFalse(dto.getQuota().getNextReplenishInSeconds() > 3600,
                "next replenish should be within an hour");
    }

    // ================================================================
    // helpers
    // ================================================================

    private static HourlyRetryBucket fakeBucket(LocalDateTime hour,
                                                Long attempts, Long retries, Long failures) {
        return new HourlyRetryBucket() {
            @Override public LocalDateTime getHourStart() { return hour; }
            @Override public Long getAttempts() { return attempts; }
            @Override public Long getRetries() { return retries; }
            @Override public Long getFailures() { return failures; }
        };
    }
}
