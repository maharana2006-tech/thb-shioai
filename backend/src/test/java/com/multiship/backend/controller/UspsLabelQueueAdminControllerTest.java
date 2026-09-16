package com.multiship.backend.controller;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.UspsLabelQueueItemDTO;
import com.multiship.backend.dto.UspsLabelQueueMetricsDTO;
import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.model.UspsLabelQueueItem.Status;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure controller tests for {@link UspsLabelQueueAdminController}. No
 * MockMvc, no Spring context - invoke methods directly and assert the
 * {@link ResponseEntity} envelope. Auth (the {@code @PreAuthorize} on
 * the class) is verified end-to-end elsewhere; these tests pin the
 * response shape + delegation.
 */
class UspsLabelQueueAdminControllerTest {

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
    // GET /metrics
    // ================================================================

    @Test
    void metrics_noTenant_returnsPlatformWideDto() {
        UspsLabelQueueMetricsDTO dto = UspsLabelQueueMetricsDTO.builder()
                .asOf(LocalDateTime.now())
                .queuedDepth(50).processingCount(2).doneCount(100)
                .failedCount(1).cancelledCount(0)
                .configuredHourlyCap(55).currentHourlyPace(53)
                .estimatedWaitSeconds(120).estimatedStartAt(LocalDateTime.now().plusMinutes(2))
                .perTenantDepth(List.of(
                        UspsLabelQueueMetricsDTO.TenantDepth.builder()
                                .tenantCode("ACME").queuedDepth(30).build(),
                        UspsLabelQueueMetricsDTO.TenantDepth.builder()
                                .tenantCode("BETA").queuedDepth(20).build()))
                .build();
        when(service.getMetrics()).thenReturn(dto);

        ResponseEntity<ApiResponse<UspsLabelQueueMetricsDTO>> resp = controller.metrics(null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("success", resp.getBody().getStatus());
        assertEquals(50L, resp.getBody().getData().getQueuedDepth());
        assertEquals(2, resp.getBody().getData().getPerTenantDepth().size());
        verify(service).getMetrics();
        verify(service, never()).getMetricsForTenant(any());
    }

    @Test
    void metrics_withTenant_delegatesToTenantScopedCall() {
        UspsLabelQueueMetricsDTO dto = UspsLabelQueueMetricsDTO.builder()
                .tenantCode("ACME").queuedDepth(10).build();
        when(service.getMetricsForTenant("ACME")).thenReturn(dto);

        ResponseEntity<ApiResponse<UspsLabelQueueMetricsDTO>> resp = controller.metrics("ACME");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("ACME", resp.getBody().getData().getTenantCode());
        verify(service).getMetricsForTenant("ACME");
        verify(service, never()).getMetrics();
    }

    @Test
    void metrics_blankTenant_treatedAsPlatformWide() {
        UspsLabelQueueMetricsDTO dto = UspsLabelQueueMetricsDTO.builder().queuedDepth(0).build();
        when(service.getMetrics()).thenReturn(dto);

        ResponseEntity<ApiResponse<UspsLabelQueueMetricsDTO>> resp = controller.metrics("   ");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(service).getMetrics();
        verify(service, never()).getMetricsForTenant(any());
    }

    // ================================================================
    // GET /items
    // ================================================================

    @Test
    void items_returnsPaginatedList() {
        List<UspsLabelQueueItem> rows = List.of(
                item(1L, "ACME", Status.QUEUED),
                item(2L, "BETA", Status.DONE));
        Page<UspsLabelQueueItem> page = new PageImpl<>(rows, PageRequest.of(0, 50), 2);
        when(repository.findAllByOrderByEnqueuedAtDesc(any(Pageable.class))).thenReturn(page);

        ResponseEntity<ApiResponse<List<UspsLabelQueueItemDTO>>> resp = controller.items(0, 50);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertNotNull(resp.getBody().getData());
        assertEquals(2, resp.getBody().getData().size());
        assertEquals(1L, resp.getBody().getData().get(0).getId());
        assertEquals("ACME", resp.getBody().getData().get(0).getTenantCode());
    }

    @Test
    void items_clampsPageSizeToMax() {
        Page<UspsLabelQueueItem> empty = new PageImpl<>(List.of(), PageRequest.of(0, 200), 0);
        when(repository.findAllByOrderByEnqueuedAtDesc(any(Pageable.class))).thenReturn(empty);

        // Asking for 10000 must be clamped to 200 (MAX_PAGE_SIZE).
        ResponseEntity<ApiResponse<List<UspsLabelQueueItemDTO>>> resp = controller.items(0, 10_000);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(repository).findAllByOrderByEnqueuedAtDesc(eq(PageRequest.of(0, 200)));
    }

    @Test
    void items_clampsNegativePageToZero() {
        Page<UspsLabelQueueItem> empty = new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
        when(repository.findAllByOrderByEnqueuedAtDesc(any(Pageable.class))).thenReturn(empty);

        controller.items(-3, 50);

        verify(repository).findAllByOrderByEnqueuedAtDesc(eq(PageRequest.of(0, 50)));
    }

    // ================================================================
    // DELETE /items/{id}
    // ================================================================

    @Test
    void cancel_onQueuedRow_returns204() {
        UspsLabelQueueItem row = item(7L, "ACME", Status.QUEUED);
        when(repository.findById(7L)).thenReturn(Optional.of(row));
        when(service.cancel(7L)).thenReturn(true);

        ResponseEntity<ApiResponse<Void>> resp = controller.cancel(7L);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("success", resp.getBody().getStatus());
        verify(service).cancel(7L);
    }

    @Test
    void cancel_onProcessingRow_returns409() {
        UspsLabelQueueItem row = item(7L, "ACME", Status.PROCESSING);
        when(repository.findById(7L)).thenReturn(Optional.of(row));
        when(service.cancel(7L)).thenReturn(false);

        ResponseEntity<ApiResponse<Void>> resp = controller.cancel(7L);

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getBody().getErrorCode());
    }

    @Test
    void cancel_onDoneRow_returns409() {
        UspsLabelQueueItem row = item(7L, "ACME", Status.DONE);
        when(repository.findById(7L)).thenReturn(Optional.of(row));
        when(service.cancel(7L)).thenReturn(false);

        ResponseEntity<ApiResponse<Void>> resp = controller.cancel(7L);

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
    }

    @Test
    void cancel_missingRow_returns404() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<Void>> resp = controller.cancel(99L);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getBody().getErrorCode());
        // Must NOT call service.cancel when the row is missing.
        verify(service, never()).cancel(any());
    }

    // ================================================================
    // helpers
    // ================================================================

    private static UspsLabelQueueItem item(long id, String tenant, Status status) {
        return UspsLabelQueueItem.builder()
                .id(id).tenantCode(tenant).shipmentId(9000L + id)
                .priority(100).status(status).retryCount(0)
                .enqueuedAt(LocalDateTime.now().minusMinutes(id))
                .build();
    }

    /**
     * Suppress unused-value warning on the "no leak on error" branch -
     * we deliberately don't inspect the body when we only care about
     * the status code.
     */
    @SuppressWarnings("unused")
    private static void assertNullBody(ApiResponse<?> body) {
        assertNull(body);
    }
}
