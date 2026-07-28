package com.multiship.backend.service;

import com.multiship.backend.model.GeneratedReport;
import com.multiship.backend.model.ScheduledReport;
import com.multiship.backend.model.ScheduledReport.Frequency;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 28, 12, 0);

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
