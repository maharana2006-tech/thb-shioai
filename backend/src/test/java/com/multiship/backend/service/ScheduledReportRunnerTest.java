package com.multiship.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.ReportFilters;
import com.multiship.backend.model.GeneratedReport;
import com.multiship.backend.model.ScheduledReport;
import com.multiship.backend.model.ScheduledReport.Dataset;
import com.multiship.backend.model.ScheduledReport.DeliveryType;
import com.multiship.backend.model.ScheduledReport.Frequency;
import com.multiship.backend.repository.GeneratedReportRepository;
import com.multiship.backend.repository.ScheduledReportRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Sprint 45 — nextRun schedule math. The runner itself is verified in
 * an integration-style smoke test on the full suite; the pure-function
 * boundary lives here.
 *
 * <p>Audit-fix #7 also verifies the webhook delivery retry semantics:
 * 3 attempts with exponential backoff, ported from the Sprint 46
 * ExternalWebhookDispatcher pattern.
 */
class ScheduledReportRunnerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 28, 12, 0, 30);

    @Test
    void daily_addsOneDay() {
        assertEquals(NOW.plusDays(1), ScheduledReportRunner.nextRun(Frequency.DAILY, NOW));
    }

    @Test
    void weekly_addsOneWeek() {
        assertEquals(NOW.plusWeeks(1), ScheduledReportRunner.nextRun(Frequency.WEEKLY, NOW));
    }

    @Test
    void monthly_addsOneMonth() {
        assertEquals(NOW.plusMonths(1), ScheduledReportRunner.nextRun(Frequency.MONTHLY, NOW));
    }

    // ===== Audit-fix #7 — webhook retry semantics =====

    private static long[] originalBackoffs;

    @BeforeAll
    static void muteBackoffs() throws Exception {
        // Zero out backoffs so the retry tests run in milliseconds.
        Field f = ScheduledReportRunner.class.getDeclaredField("webhookBackoffsMs");
        f.setAccessible(true);
        originalBackoffs = (long[]) f.get(null);
        f.set(null, new long[]{ 0L, 0L, 0L });
    }

    @AfterAll
    static void restoreBackoffs() throws Exception {
        Field f = ScheduledReportRunner.class.getDeclaredField("webhookBackoffsMs");
        f.setAccessible(true);
        f.set(null, originalBackoffs);
    }

    @Test
    void deliverWebhookWithRetry_successOnFirstAttempt_singlePost() throws Exception {
        RestClient client = restClientReturning(HttpStatus.OK, null);
        ScheduledReportRunner runner = runnerWith(client);

        runner.deliverWebhookWithRetry(schedule(), generated(), "csv".getBytes());

        verify(client, times(1)).post();
    }

    @Test
    void deliverWebhookWithRetry_retriesTransientFailures_upToMaxAttempts() throws Exception {
        RuntimeException boom = new RuntimeException("connect timeout");
        RestClient client = restClientAlwaysThrowing(boom);
        ScheduledReportRunner runner = runnerWith(client);

        Exception thrown = assertThrows(Exception.class,
                () -> runner.deliverWebhookWithRetry(schedule(), generated(), "csv".getBytes()));
        assertEquals("connect timeout", thrown.getMessage(),
                "final error should surface the underlying cause");
        verify(client, times(ScheduledReportRunner.WEBHOOK_MAX_ATTEMPTS)).post();
    }

    @Test
    void deliverWebhookWithRetry_non2xxCountsAsFailure_thenRetries() throws Exception {
        RestClient client = restClientReturning(HttpStatus.INTERNAL_SERVER_ERROR, null);
        ScheduledReportRunner runner = runnerWith(client);

        Exception thrown = assertThrows(Exception.class,
                () -> runner.deliverWebhookWithRetry(schedule(), generated(), "csv".getBytes()));
        assertTrue(thrown.getMessage().contains("500"),
                "final error should name the HTTP status: " + thrown.getMessage());
        verify(client, times(ScheduledReportRunner.WEBHOOK_MAX_ATTEMPTS)).post();
    }

    // ===== helpers =====

    /** Build a runner and set its private testRestClient field via reflection. */
    private static ScheduledReportRunner runnerWith(RestClient client) throws Exception {
        ScheduledReportRunner runner = new ScheduledReportRunner(
                null, null, null, null);
        Field f = ScheduledReportRunner.class.getDeclaredField("testRestClient");
        f.setAccessible(true);
        f.set(runner, client);
        return runner;
    }

    private static ScheduledReport schedule() {
        ScheduledReport s = new ScheduledReport();
        s.setId(1L);
        s.setDeliveryWebhookUrl("https://receiver.example/wh");
        return s;
    }

    // ===== G6 — tenant scope enforcement =====

    @Test
    void runOne_scheduleTenantIdOverridesFiltersCustomerNo() throws Exception {
        // Schedule scoped to ACME, but filtersJson names a different tenant
        // (COMPETITOR). Runner must force customerNo=ACME to prevent the leak.
        ScheduledReportRepository sRepo = mock(ScheduledReportRepository.class);
        GeneratedReportRepository gRepo = mock(GeneratedReportRepository.class);
        ReportService reportService = mock(ReportService.class);
        ObjectMapper mapper = new ObjectMapper();

        ScheduledReportRunner runner = new ScheduledReportRunner(sRepo, gRepo, reportService, mapper);

        ScheduledReport s = new ScheduledReport();
        s.setId(42L);
        s.setTenantId("ACME");
        s.setDataset(Dataset.ORDERS);
        s.setFrequency(Frequency.DAILY);
        s.setDeliveryType(DeliveryType.DASHBOARD);
        s.setCreatedByRole("ROLE_TENANT");
        s.setFiltersJson(mapper.writeValueAsString(ReportFilters.builder()
                .customerNo("COMPETITOR").build()));

        runner.runOne(s, NOW);

        // Assert reportService was called with customerNo=ACME (the schedule
        // won), not COMPETITOR (the filtersJson).
        ArgumentCaptor<ReportFilters> captor = ArgumentCaptor.forClass(ReportFilters.class);
        verify(reportService).streamOrdersCsv(captor.capture(), any(OutputStream.class));
        assertEquals("ACME", captor.getValue().getCustomerNo(),
                "schedule tenantId must override any stored filters.customerNo");
    }

    @Test
    void runOne_scheduleTenantIdPopulatesEmptyCustomerNo() throws Exception {
        // Filters had no customerNo → schedule's tenantId is injected.
        ScheduledReportRepository sRepo = mock(ScheduledReportRepository.class);
        GeneratedReportRepository gRepo = mock(GeneratedReportRepository.class);
        ReportService reportService = mock(ReportService.class);
        ScheduledReportRunner runner = new ScheduledReportRunner(sRepo, gRepo, reportService, new ObjectMapper());

        ScheduledReport s = new ScheduledReport();
        s.setId(43L);
        s.setTenantId("ACME");
        s.setDataset(Dataset.ORDERS);
        s.setFrequency(Frequency.DAILY);
        s.setDeliveryType(DeliveryType.DASHBOARD);

        runner.runOne(s, NOW);

        ArgumentCaptor<ReportFilters> captor = ArgumentCaptor.forClass(ReportFilters.class);
        verify(reportService).streamOrdersCsv(captor.capture(), any(OutputStream.class));
        assertEquals("ACME", captor.getValue().getCustomerNo());
    }

    @Test
    void runOne_platformScheduleFromAdminOrUserPassesThroughUnchanged() throws Exception {
        // No tenantId + creator was ROLE_ADMIN → runs as-is (customerNo null).
        ScheduledReportRepository sRepo = mock(ScheduledReportRepository.class);
        GeneratedReportRepository gRepo = mock(GeneratedReportRepository.class);
        ReportService reportService = mock(ReportService.class);
        ScheduledReportRunner runner = new ScheduledReportRunner(sRepo, gRepo, reportService, new ObjectMapper());

        ScheduledReport s = new ScheduledReport();
        s.setId(44L);
        s.setDataset(Dataset.ORDERS);
        s.setFrequency(Frequency.DAILY);
        s.setDeliveryType(DeliveryType.DASHBOARD);
        s.setCreatedByRole("ROLE_ADMIN");

        runner.runOne(s, NOW);

        ArgumentCaptor<ReportFilters> captor = ArgumentCaptor.forClass(ReportFilters.class);
        verify(reportService).streamOrdersCsv(captor.capture(), any(OutputStream.class));
        assertNull(captor.getValue().getCustomerNo(),
                "platform-scoped ADMIN schedule keeps customerNo=null");
    }

    @Test
    void runOne_tenantCreatorWithoutTenantIdRefusesToRun() {
        // A TENANT-created schedule that somehow lost its tenantId — refuse to
        // run rather than pull cross-tenant data.
        ScheduledReportRepository sRepo = mock(ScheduledReportRepository.class);
        GeneratedReportRepository gRepo = mock(GeneratedReportRepository.class);
        ReportService reportService = mock(ReportService.class);
        ScheduledReportRunner runner = new ScheduledReportRunner(sRepo, gRepo, reportService, new ObjectMapper());

        ScheduledReport s = new ScheduledReport();
        s.setId(45L);
        s.setDataset(Dataset.ORDERS);
        s.setFrequency(Frequency.DAILY);
        s.setDeliveryType(DeliveryType.DASHBOARD);
        s.setCreatedByRole("ROLE_TENANT");
        // deliberately no tenantId

        assertThrows(IllegalStateException.class, () -> runner.runOne(s, NOW));
        verify(reportService, never()).streamOrdersCsv(any(), any(OutputStream.class));
    }

    private static GeneratedReport generated() {
        GeneratedReport g = new GeneratedReport();
        g.setFilename("orders-2026-07-28.csv");
        g.setDataset("ORDERS");
        return g;
    }

    /**
     * A RestClient chain that terminates in the given status via
     * {@code retrieve().toBodilessEntity()}. Every step is a mock so we
     * can count how many times .post() ran.
     */
    private static RestClient restClientReturning(HttpStatus status, String body) {
        RestClient client = mock(RestClient.class);
        RestClient.RequestBodyUriSpec spec = mock(RestClient.RequestBodyUriSpec.class,
                withSettings().defaultAnswer(RETURNS_SELF));
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);
        when(client.post()).thenReturn(spec);
        when(spec.uri(anyString())).thenReturn(spec);
        when(spec.header(anyString(), anyString())).thenReturn(spec);
        when(spec.body(any(byte[].class))).thenReturn(spec);
        when(spec.retrieve()).thenReturn(respSpec);
        ResponseEntity<Void> resp = ResponseEntity.status(status).build();
        when(respSpec.toBodilessEntity()).thenReturn(resp);
        return client;
    }

    private static RestClient restClientAlwaysThrowing(RuntimeException err) {
        RestClient client = mock(RestClient.class);
        RestClient.RequestBodyUriSpec spec = mock(RestClient.RequestBodyUriSpec.class,
                withSettings().defaultAnswer(RETURNS_SELF));
        RestClient.ResponseSpec respSpec = mock(RestClient.ResponseSpec.class);
        when(client.post()).thenReturn(spec);
        when(spec.uri(anyString())).thenReturn(spec);
        when(spec.header(anyString(), anyString())).thenReturn(spec);
        when(spec.body(any(byte[].class))).thenReturn(spec);
        when(spec.retrieve()).thenReturn(respSpec);
        when(respSpec.toBodilessEntity()).thenThrow(err);
        return client;
    }
}
