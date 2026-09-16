package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.UspsDashboardMetricsDTO;
import com.multiship.backend.dto.UspsLabelQueueMetricsDTO;
import com.multiship.backend.dto.UspsQuotaHeadroomDTO;
import com.multiship.backend.dto.UspsReconciliationRollupDTO;
import com.multiship.backend.dto.UspsRetryBucketDTO;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import com.multiship.backend.service.carriers.usps.UspsDirectVoidReconciliationService;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-F4 - pure-controller tests for the new
 * {@code /admin/usps-direct/dashboard/*} endpoints. Follows the sibling
 * {@link UspsLabelQueueAdminControllerTest} pattern (no MockMvc, no
 * Spring context) - invoke methods directly and assert the
 * {@link ResponseEntity} envelope + delegation.
 *
 * <p>Auth ({@code @PreAuthorize("hasRole('ADMIN')")}) is verified
 * end-to-end via Spring security tests elsewhere; the FORBIDDEN /
 * 403 branch cannot be exercised without the security filter chain,
 * so we document + verify the {@code @PreAuthorize} annotation is
 * present via reflection instead of firing an HTTP request.
 */
class UspsLabelQueueAdminControllerDashboardTest {

    private UspsLabelQueueService service;
    private UspsLabelQueueRepository repository;
    private UspsDirectVoidReconciliationService reconciliationService;
    private UspsLabelQueueAdminController controller;

    @BeforeEach
    void setUp() {
        service = mock(UspsLabelQueueService.class);
        repository = mock(UspsLabelQueueRepository.class);
        reconciliationService = mock(UspsDirectVoidReconciliationService.class);
        controller = new UspsLabelQueueAdminController(service, repository, reconciliationService);
    }

    // ================================================================
    // GET /dashboard
    // ================================================================

    @Test
    void dashboard_noParams_defaultsTo24hAnd30d() {
        UspsDashboardMetricsDTO dto = compositeDto();
        when(service.getDashboardMetrics(any(), any())).thenReturn(dto);

        ResponseEntity<ApiResponse<UspsDashboardMetricsDTO>> resp =
                controller.dashboard(null, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("success", resp.getBody().getStatus());
        assertNotNull(resp.getBody().getData().getQueue());

        ArgumentCaptor<Duration> retryCap = ArgumentCaptor.forClass(Duration.class);
        ArgumentCaptor<Duration> reconCap = ArgumentCaptor.forClass(Duration.class);
        verify(service).getDashboardMetrics(retryCap.capture(), reconCap.capture());
        assertEquals(Duration.ofHours(24), retryCap.getValue());
        assertEquals(Duration.ofDays(30), reconCap.getValue());
    }

    @Test
    void dashboard_customLookbacks_honored() {
        UspsDashboardMetricsDTO dto = compositeDto();
        when(service.getDashboardMetrics(any(), any())).thenReturn(dto);

        controller.dashboard(48, 7);

        verify(service).getDashboardMetrics(eq(Duration.ofHours(48)), eq(Duration.ofDays(7)));
    }

    @Test
    void dashboard_negativeParams_fallBackToDefaults() {
        UspsDashboardMetricsDTO dto = compositeDto();
        when(service.getDashboardMetrics(any(), any())).thenReturn(dto);

        controller.dashboard(-5, -1);

        verify(service).getDashboardMetrics(eq(Duration.ofHours(24)), eq(Duration.ofDays(30)));
    }

    // ================================================================
    // GET /dashboard/quota-headroom
    // ================================================================

    @Test
    void quotaHeadroom_returnsQuotaSubDto() {
        UspsDashboardMetricsDTO composite = compositeDto();
        when(service.getDashboardMetrics(any(), any())).thenReturn(composite);

        ResponseEntity<ApiResponse<UspsQuotaHeadroomDTO>> resp = controller.quotaHeadroom();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertNotNull(resp.getBody().getData());
        assertEquals(55L, resp.getBody().getData().getHourlyCap());
        assertEquals(42, resp.getBody().getData().getRemainingTokens());
    }

    // ================================================================
    // GET /dashboard/retry-buckets
    // ================================================================

    @Test
    void retryBuckets_default24h_delegatesToService() {
        UspsRetryBucketDTO retries = UspsRetryBucketDTO.builder()
                .hoursLookback(24).buckets(List.of()).build();
        when(service.getRetryBuckets(any())).thenReturn(retries);

        ResponseEntity<ApiResponse<UspsRetryBucketDTO>> resp = controller.retryBuckets(null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(24, resp.getBody().getData().getHoursLookback());
        verify(service).getRetryBuckets(eq(Duration.ofHours(24)));
    }

    @Test
    void retryBuckets_customLookback_honored() {
        UspsRetryBucketDTO retries = UspsRetryBucketDTO.builder()
                .hoursLookback(72).buckets(List.of()).build();
        when(service.getRetryBuckets(any())).thenReturn(retries);

        controller.retryBuckets(72);

        verify(service).getRetryBuckets(eq(Duration.ofHours(72)));
    }

    @Test
    void retryBuckets_zeroLookback_fallsBackToDefault() {
        UspsRetryBucketDTO retries = UspsRetryBucketDTO.builder()
                .hoursLookback(24).buckets(List.of()).build();
        when(service.getRetryBuckets(any())).thenReturn(retries);

        controller.retryBuckets(0);

        verify(service).getRetryBuckets(eq(Duration.ofHours(24)));
    }

    // ================================================================
    // GET /dashboard/reconciliation-rollup
    // ================================================================

    @Test
    void reconciliationRollup_default30d_delegatesToService() {
        UspsReconciliationRollupDTO rollup = UspsReconciliationRollupDTO.builder()
                .lookbackDays(30)
                .voidedShipmentsInWindow(0L)
                .reconciledApproved(0L).reconciledDenied(0L).notYetReconciled(0L)
                .pendingRefundValue(BigDecimal.ZERO).currency("USD")
                .build();
        when(reconciliationService.getRollup(any())).thenReturn(rollup);

        ResponseEntity<ApiResponse<UspsReconciliationRollupDTO>> resp =
                controller.reconciliationRollup(null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(30, resp.getBody().getData().getLookbackDays());
        verify(reconciliationService).getRollup(eq(Duration.ofDays(30)));
    }

    @Test
    void reconciliationRollup_customLookback_honored() {
        UspsReconciliationRollupDTO rollup = UspsReconciliationRollupDTO.builder()
                .lookbackDays(7)
                .voidedShipmentsInWindow(5L)
                .reconciledApproved(4L).reconciledDenied(0L).notYetReconciled(1L)
                .pendingRefundValue(new BigDecimal("10.00")).currency("USD")
                .build();
        when(reconciliationService.getRollup(any())).thenReturn(rollup);

        ResponseEntity<ApiResponse<UspsReconciliationRollupDTO>> resp =
                controller.reconciliationRollup(7);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(7, resp.getBody().getData().getLookbackDays());
        verify(reconciliationService).getRollup(eq(Duration.ofDays(7)));
    }

    @Test
    void reconciliationRollup_nullService_gracefullyDegrades() {
        // Simulate the legacy-constructor path: no reconciliation
        // service. The endpoint returns an empty rollup rather than
        // 500ing.
        UspsLabelQueueAdminController legacy =
                new UspsLabelQueueAdminController(service, repository);

        ResponseEntity<ApiResponse<UspsReconciliationRollupDTO>> resp =
                legacy.reconciliationRollup(30);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().getData());
        assertEquals(0L, resp.getBody().getData().getVoidedShipmentsInWindow());
        assertEquals("USD", resp.getBody().getData().getCurrency());
    }

    // ================================================================
    // Security annotation surface
    // ================================================================

    @Test
    void classLevelPreAuthorize_requiresAdmin() throws Exception {
        // The @PreAuthorize("hasRole('ADMIN')") on the class enforces
        // 403 for non-ADMIN callers via Spring security's method
        // interceptor. Assert the annotation is present so a well-
        // meaning refactor doesn't accidentally strip it.
        org.springframework.security.access.prepost.PreAuthorize annotation =
                UspsLabelQueueAdminController.class
                        .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        assertNotNull(annotation, "class-level @PreAuthorize must be present so non-ADMIN callers 403");
        assertTrue(annotation.value().contains("ADMIN"),
                "class-level @PreAuthorize must require ADMIN role");
    }

    // ================================================================
    // helpers
    // ================================================================

    private static UspsDashboardMetricsDTO compositeDto() {
        return UspsDashboardMetricsDTO.builder()
                .generatedAt(Instant.now())
                .queue(UspsLabelQueueMetricsDTO.builder()
                        .queuedDepth(10).processingCount(1)
                        .configuredHourlyCap(55).build())
                .quota(UspsQuotaHeadroomDTO.builder()
                        .hourlyCap(55).remainingTokens(42)
                        .utilizationPercent(new BigDecimal("23.6"))
                        .lastReplenishAt(Instant.now())
                        .nextReplenishInSeconds(0)
                        .build())
                .retries(UspsRetryBucketDTO.builder()
                        .hoursLookback(24).buckets(List.of())
                        .build())
                .reconciliation(UspsReconciliationRollupDTO.builder()
                        .lookbackDays(30)
                        .voidedShipmentsInWindow(0L)
                        .reconciledApproved(0L).reconciledDenied(0L).notYetReconciled(0L)
                        .pendingRefundValue(BigDecimal.ZERO).currency("USD")
                        .build())
                .build();
    }
}
