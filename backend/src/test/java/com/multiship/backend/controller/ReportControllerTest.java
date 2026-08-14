package com.multiship.backend.controller;

import com.multiship.backend.common.CsvMediaType;
import com.multiship.backend.service.ReportService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 AC-M6 — smoke tests for ReportController (was 0-coverage).
 * The controller is a thin adapter over ReportService that wires:
 *   - CSV content-type/disposition headers
 *   - Filter-query → ReportFilters binding (date parsing branches)
 *   - Streaming into HttpServletResponse.getOutputStream() (no buffering)
 */
class ReportControllerTest {

    private ReportService reportService;
    private ReportController controller;
    private HttpServletResponse response;
    private CapturingServletOutputStream out;

    @BeforeEach
    void setUp() throws IOException {
        reportService = mock(ReportService.class);
        controller = new ReportController(reportService);
        response = mock(HttpServletResponse.class);
        out = new CapturingServletOutputStream();
        when(response.getOutputStream()).thenReturn(out);
    }

    // ===== orders =====

    @Test
    void orders_setsCsvHeadersAndDelegatesStream() throws IOException {
        ReportController.ReportFiltersQuery q = new ReportController.ReportFiltersQuery();
        q.setFrom("2026-08-01");
        q.setTo("2026-08-13");

        controller.orders(response, q);

        verify(response).setContentType(CsvMediaType.CSV_UTF8);
        verify(response).setCharacterEncoding("UTF-8");
        verify(response).setHeader(eq("Content-Disposition"),
                contains("attachment; filename=orders-" + LocalDate.now()));
        verify(reportService).streamOrdersCsv(any(), eq(out));
    }

    @Test
    void tracking_setsDatasetSpecificFilename() throws IOException {
        controller.tracking(response, new ReportController.ReportFiltersQuery());

        verify(response).setHeader(eq("Content-Disposition"),
                contains("tracking-" + LocalDate.now()));
        verify(reportService).streamTrackingCsv(any(), eq(out));
    }

    @Test
    void rateShop_delegatesToRateShopStream() throws IOException {
        controller.rateShop(response, new ReportController.ReportFiltersQuery());

        verify(response).setHeader(eq("Content-Disposition"), contains("rate-shop-"));
        verify(reportService).streamRateShopCsv(any(), eq(out));
    }

    @Test
    void billing_delegatesToBillingStream() throws IOException {
        controller.billing(response, new ReportController.ReportFiltersQuery());

        verify(response).setHeader(eq("Content-Disposition"), contains("billing-"));
        verify(reportService).streamBillingCsv(any(), eq(out));
    }

    // ===== filter parsing =====

    @Test
    void reportFiltersQuery_parsesDateAndDatetime() {
        ReportController.ReportFiltersQuery q = new ReportController.ReportFiltersQuery();
        q.setFrom("2026-08-01");                    // 10-char → start of day
        q.setTo("2026-08-13T15:30:00");             // ISO datetime
        q.setCustomerNo("ACME");
        q.setCarrier("UPS");

        var filters = q.toFilters();

        assertNotNull(filters);
        assertEquals("ACME", filters.getCustomerNo());
        assertEquals("UPS", filters.getCarrier());
    }

    @Test
    void reportFiltersQuery_blankAndNullDatesResolveToNull() {
        ReportController.ReportFiltersQuery q = new ReportController.ReportFiltersQuery();
        q.setFrom(null);
        q.setTo("");

        var filters = q.toFilters();

        assertNotNull(filters);
        // Dates are null-safe when omitted (a common REST usage).
    }

    // ===== helper — ServletOutputStream that just accumulates bytes =====

    private static class CapturingServletOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        @Override public boolean isReady() { return true; }
        @Override public void setWriteListener(WriteListener writeListener) {}
        @Override public void write(int b) { baos.write(b); }
        // Package-private accessor if needed later.
        byte[] toByteArray() { return baos.toByteArray(); }
    }
}
