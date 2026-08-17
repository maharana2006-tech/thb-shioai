package com.multiship.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.ScheduledReportDTO;
import com.multiship.backend.model.ScheduledReport;
import com.multiship.backend.model.ScheduledReport.DeliveryType;
import com.multiship.backend.repository.GeneratedReportRepository;
import com.multiship.backend.repository.ScheduledReportRepository;
import com.multiship.backend.service.ScheduledReportRunner;
import com.multiship.backend.service.TenantScopeEnforcer;
import com.multiship.backend.service.external.WebhookUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit B1 / B3 / B7 / B9 — controller-level guard tests for the new
 * save validation + cross-tenant delete refusal.
 */
class ScheduledReportControllerTest {

    private ScheduledReportRepository scheduleRepo;
    private GeneratedReportRepository generatedRepo;
    private ScheduledReportRunner runner;
    private TenantScopeEnforcer tenantScope;
    private WebhookUrlValidator webhookUrlValidator;
    private ObjectMapper objectMapper;
    private ScheduledReportController controller;

    @BeforeEach
    void setUp() {
        scheduleRepo = mock(ScheduledReportRepository.class);
        generatedRepo = mock(GeneratedReportRepository.class);
        runner = mock(ScheduledReportRunner.class);
        tenantScope = mock(TenantScopeEnforcer.class);
        webhookUrlValidator = mock(WebhookUrlValidator.class);
        objectMapper = new ObjectMapper();
        controller = new ScheduledReportController(
                scheduleRepo, generatedRepo, runner, tenantScope,
                webhookUrlValidator, objectMapper);
        // TenantScope pass-through by default (platform ADMIN).
        when(tenantScope.clampClientCode(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(tenantScope.clampClientCode(null)).thenReturn(null);
    }

    // ===== B9 delivery-type validation =====

    @Test
    void save_webhookWithoutUrlReturns400() {
        ScheduledReportDTO body = ScheduledReportDTO.builder()
                .name("bad").deliveryType(DeliveryType.WEBHOOK).build();

        ResponseEntity<ApiResponse<ScheduledReportDTO>> resp = controller.save(body);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getBody().getErrorCode());
    }

    @Test
    void save_emailWithoutRecipientReturns400() {
        ScheduledReportDTO body = ScheduledReportDTO.builder()
                .name("bad").deliveryType(DeliveryType.EMAIL).build();

        ResponseEntity<ApiResponse<ScheduledReportDTO>> resp = controller.save(body);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    // ===== B7 SSRF guard =====

    @Test
    void save_webhookWithBlockedUrlReturns400() {
        ScheduledReportDTO body = ScheduledReportDTO.builder()
                .name("ssrf").deliveryType(DeliveryType.WEBHOOK)
                .deliveryWebhookUrl("http://169.254.169.254/latest/").build();
        doThrow(new WebhookUrlValidator.WebhookUrlRejectedException("blocked: metadata host"))
                .when(webhookUrlValidator).validate(anyString());

        ResponseEntity<ApiResponse<ScheduledReportDTO>> resp = controller.save(body);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(scheduleRepo, never()).save(any());
    }

    // ===== B3 filtersJson validation =====

    @Test
    void save_malformedFiltersJsonReturns400() {
        ScheduledReportDTO body = ScheduledReportDTO.builder()
                .name("bad").deliveryType(DeliveryType.DASHBOARD)
                .filtersJson("{not json").build();

        ResponseEntity<ApiResponse<ScheduledReportDTO>> resp = controller.save(body);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(scheduleRepo, never()).save(any());
    }

    // ===== B1 cross-tenant delete =====

    @Test
    void delete_missingIdReturns404() {
        when(scheduleRepo.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<Void>> resp = controller.delete(99L);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        verify(scheduleRepo, never()).delete(any());
    }

    @Test
    void delete_platformAdminCanDeleteAnyTenantRow() {
        ScheduledReport row = new ScheduledReport();
        row.setId(7L);
        row.setTenantId("ACME");
        when(scheduleRepo.findById(7L)).thenReturn(Optional.of(row));
        // platform ADMIN: tenantScope.requireTenantMatch is a no-op

        ResponseEntity<ApiResponse<Void>> resp = controller.delete(7L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(tenantScope).requireTenantMatch("ACME");
        verify(scheduleRepo).delete(row);
    }

    @Test
    void delete_crossTenantScopedUser_throws() {
        ScheduledReport row = new ScheduledReport();
        row.setId(7L);
        row.setTenantId("ACME");
        when(scheduleRepo.findById(7L)).thenReturn(Optional.of(row));
        doThrow(new org.springframework.security.access.AccessDeniedException("cross-tenant"))
                .when(tenantScope).requireTenantMatch("ACME");

        try {
            controller.delete(7L);
        } catch (org.springframework.security.access.AccessDeniedException expected) {
            // OK — Spring translates to 403 via the default AuthenticationEntryPoint.
        }
        verify(scheduleRepo, never()).delete(any());
    }
}
