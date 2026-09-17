package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.UspsFallbackAlertDTO;
import com.multiship.backend.dto.UspsLabelQueueItemDTO;
import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.model.UspsLabelQueueItem.Status;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import com.multiship.backend.service.carriers.usps.UspsDirectVoidReconciliationService;
import com.multiship.backend.service.carriers.usps.queue.UspsFallbackAlertService;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-G3b — pins the two admin-controller additions on top of the PR-F
 * surface:
 * <ol>
 *   <li>{@code /queue/items?sourceType=X} filters by originating surface
 *       and gracefully degrades to unfiltered on blank / unknown values.</li>
 *   <li>{@code /dashboard/fallback-alerts} returns the ring-buffer
 *       snapshot from {@link UspsFallbackAlertService} (empty when the
 *       bean is absent).</li>
 * </ol>
 *
 * <p>Pure-Mockito controller test — no MockMvc, no Spring context.
 * {@code @PreAuthorize} enforcement is verified end-to-end elsewhere.
 */
class UspsLabelQueueAdminControllerBySourceTest {

    private UspsLabelQueueService service;
    private UspsLabelQueueRepository repository;
    private UspsDirectVoidReconciliationService reconciliation;
    private UspsFallbackAlertService alerts;
    private UspsLabelQueueAdminController controller;

    @BeforeEach
    void setUp() {
        service = mock(UspsLabelQueueService.class);
        repository = mock(UspsLabelQueueRepository.class);
        reconciliation = mock(UspsDirectVoidReconciliationService.class);
        alerts = mock(UspsFallbackAlertService.class);
        controller = new UspsLabelQueueAdminController(service, repository, reconciliation, alerts);
    }

    // ================================================================
    // /queue/items?sourceType=X
    // ================================================================

    @Test
    void items_blankSourceType_fallsBackToUnfilteredList() {
        Page<UspsLabelQueueItem> page = new PageImpl<>(List.of(sampleRow(1L, UspsLabelQueueItem.SourceType.MANUAL)));
        when(repository.findAllByOrderByEnqueuedAtDesc(any(Pageable.class))).thenReturn(page);

        ResponseEntity<ApiResponse<List<UspsLabelQueueItemDTO>>> resp =
                controller.items(0, 50, "  ");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(1, resp.getBody().getData().size());
        verify(repository).findAllByOrderByEnqueuedAtDesc(any(Pageable.class));
        verify(repository, never()).findBySourceTypeOrderByEnqueuedAtDesc(any(), any());
    }

    @Test
    void items_unknownSourceType_fallsBackToUnfilteredList() {
        Page<UspsLabelQueueItem> page = new PageImpl<>(List.of());
        when(repository.findAllByOrderByEnqueuedAtDesc(any(Pageable.class))).thenReturn(page);

        ResponseEntity<ApiResponse<List<UspsLabelQueueItemDTO>>> resp =
                controller.items(0, 50, "NOT_A_REAL_SOURCE");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(repository).findAllByOrderByEnqueuedAtDesc(any(Pageable.class));
        verify(repository, never()).findBySourceTypeOrderByEnqueuedAtDesc(any(), any());
    }

    @Test
    void items_knownSourceType_delegatesToScopedQuery() {
        Page<UspsLabelQueueItem> page = new PageImpl<>(List.of(
                sampleRow(11L, UspsLabelQueueItem.SourceType.IMPORT_BACKGROUND),
                sampleRow(12L, UspsLabelQueueItem.SourceType.IMPORT_BACKGROUND)));
        when(repository.findBySourceTypeOrderByEnqueuedAtDesc(
                eq(UspsLabelQueueItem.SourceType.IMPORT_BACKGROUND), any(Pageable.class)))
                .thenReturn(page);

        ResponseEntity<ApiResponse<List<UspsLabelQueueItemDTO>>> resp =
                controller.items(0, 50, "import_background");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(2, resp.getBody().getData().size());
        verify(repository).findBySourceTypeOrderByEnqueuedAtDesc(
                eq(UspsLabelQueueItem.SourceType.IMPORT_BACKGROUND), any(Pageable.class));
        verify(repository, never()).findAllByOrderByEnqueuedAtDesc(any(Pageable.class));
    }

    @Test
    void items_pageSizeCappedAtMax() {
        when(repository.findAllByOrderByEnqueuedAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller.items(0, 5_000, null);

        verify(repository).findAllByOrderByEnqueuedAtDesc(
                argThat(p -> p.getPageSize() == 200));
    }

    // ================================================================
    // /dashboard/fallback-alerts
    // ================================================================

    @Test
    void fallbackAlerts_beanPresent_returnsRingBufferSnapshot() {
        UspsFallbackAlertDTO a1 = UspsFallbackAlertDTO.builder()
                .occurredAt(Instant.now())
                .orderNo(100L)
                .tenantCode("ACME")
                .importBatchId(42L)
                .reason("routing returned SYNC for USPS carrier under USPS_DIRECT")
                .source("IMPORT_BACKGROUND")
                .build();
        when(alerts.recentAlerts()).thenReturn(List.of(a1));

        ResponseEntity<ApiResponse<List<UspsFallbackAlertDTO>>> resp = controller.fallbackAlerts();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(1, resp.getBody().getData().size());
        assertEquals("IMPORT_BACKGROUND", resp.getBody().getData().get(0).getSource());
        verify(alerts).recentAlerts();
    }

    @Test
    void fallbackAlerts_beanAbsent_returnsEmptyList() {
        UspsLabelQueueAdminController legacyCtrl =
                new UspsLabelQueueAdminController(service, repository);  // 2-arg legacy path

        ResponseEntity<ApiResponse<List<UspsFallbackAlertDTO>>> resp = legacyCtrl.fallbackAlerts();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().getData().isEmpty());
    }

    // ================================================================
    // helpers
    // ================================================================

    private static UspsLabelQueueItem sampleRow(long id, UspsLabelQueueItem.SourceType src) {
        UspsLabelQueueItem row = new UspsLabelQueueItem();
        row.setId(id);
        row.setTenantCode("ACME");
        row.setShipmentId(id * 10);
        row.setStatus(Status.QUEUED);
        row.setPriority(100);
        row.setRetryCount(0);
        row.setEnqueuedAt(LocalDateTime.now());
        row.setSourceType(src);
        return row;
    }

    // Local helper — avoid pulling in ArgumentMatchers.argThat static import
    // for one call site, but Mockito's argThat accepts a lambda.
    private static <T> T argThat(java.util.function.Predicate<T> p) {
        return org.mockito.ArgumentMatchers.argThat(p::test);
    }
}
