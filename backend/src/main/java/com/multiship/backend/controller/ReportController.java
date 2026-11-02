package com.multiship.backend.controller;

import com.multiship.backend.dto.ReportFilters;
import com.multiship.backend.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Sprint 45 — on-demand CSV report streaming. Every dataset takes the
 * same filter query params (all optional). Endpoints stream directly
 * into the HttpServletResponse so large exports don't buffer.
 */
@Tag(name = "Reports", description = "On-demand CSV report streams (Sprint 45)")
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "Orders + labels dataset (CSV stream)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/orders")
    public void orders(HttpServletResponse response, ReportFiltersQuery q) throws IOException {
        reportService.streamOrdersCsv(q.toFilters(), new DeferredCsvOutput(response, "orders"));
    }

    @Operation(summary = "Tracking snapshots dataset (CSV stream)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/tracking")
    public void tracking(HttpServletResponse response, ReportFiltersQuery q) throws IOException {
        reportService.streamTrackingCsv(q.toFilters(), new DeferredCsvOutput(response, "tracking"));
    }

    @Operation(summary = "Rate-shop history dataset (CSV stream)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/rate-shop")
    public void rateShop(HttpServletResponse response, ReportFiltersQuery q) throws IOException {
        reportService.streamRateShopCsv(q.toFilters(), new DeferredCsvOutput(response, "rate-shop"));
    }

    @Operation(summary = "Client billing rollup dataset (CSV stream)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/billing")
    public void billing(HttpServletResponse response, ReportFiltersQuery q) throws IOException {
        reportService.streamBillingCsv(q.toFilters(), new DeferredCsvOutput(response, "billing"));
    }

    /**
     * BUG-166 — the CSV headers used to be written before the query ran, so
     * a SQL failure left the client a committed 200 with an empty file and
     * the error handler could not write JSON into a text/csv response. The
     * headers are now set on the first byte the service writes, which the
     * service only does once the query has returned its first row; a
     * failure before that is an ordinary JSON error. (The mappings also no
     * longer declare {@code produces = text/csv} for the same reason.)
     */
    static final class DeferredCsvOutput extends java.io.OutputStream {
        private final HttpServletResponse response;
        private final String dataset;
        private java.io.OutputStream delegate;

        DeferredCsvOutput(HttpServletResponse response, String dataset) {
            this.response = response;
            this.dataset = dataset;
        }

        private java.io.OutputStream out() throws IOException {
            if (delegate == null) {
                setCsvHeaders(response, dataset);
                delegate = response.getOutputStream();
            }
            return delegate;
        }

        @Override public void write(int b) throws IOException { out().write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out().write(b, off, len); }
        @Override public void flush() throws IOException { if (delegate != null) delegate.flush(); }
        @Override public void close() throws IOException { if (delegate != null) delegate.close(); }
    }

    private static void setCsvHeaders(HttpServletResponse response, String dataset) {
        // Sprint 51 AC-L5 — canonical UTF-8 CSV content-type.
        response.setContentType(com.multiship.backend.common.CsvMediaType.CSV_UTF8);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=" + dataset + "-" + LocalDate.now() + ".csv");
    }

    /** Bind-target for report filter query params. Spring binds by setter. */
    @lombok.Data
    public static class ReportFiltersQuery {
        private String from;
        private String to;
        private String customerNo;
        private String carrier;
        private String service;
        private String status;
        private String originCountry;
        private String destCountry;
        private BigDecimal minWeightLb;
        private BigDecimal maxWeightLb;
        private BigDecimal minDeclaredValue;
        private BigDecimal maxDeclaredValue;

        public ReportFilters toFilters() {
            LocalDateTime fromTs = parseDate(from, "from");
            LocalDateTime toTs = parseDate(to, "to");
            if (fromTs != null && toTs != null && fromTs.isAfter(toTs)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "from must be on or before to");
            }
            return ReportFilters.builder()
                    .from(fromTs)
                    .to(toTs)
                    .customerNo(customerNo)
                    .carrier(carrier)
                    .service(service)
                    .status(status)
                    .originCountry(originCountry)
                    .destCountry(destCountry)
                    .minWeightLb(minWeightLb)
                    .maxWeightLb(maxWeightLb)
                    .minDeclaredValue(minDeclaredValue)
                    .maxDeclaredValue(maxDeclaredValue)
                    .build();
        }

        /** yyyy-MM-dd (start of day) or an ISO date-time; anything else is a 400 naming the box (BUG-168). */
        private static LocalDateTime parseDate(String s, String field) {
            if (s == null || s.isBlank()) return null;
            try {
                if (s.length() == 10) return LocalDate.parse(s).atStartOfDay();
                return LocalDateTime.parse(s);
            } catch (java.time.format.DateTimeParseException ex) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        field + ": use yyyy-MM-dd or an ISO date-time such as 2026-10-25T16:00");
            }
        }
    }

    // Also unused fields exposed on MediaType for consistency
    @SuppressWarnings("unused")
    private static final MediaType TEXT_CSV = MediaType.valueOf("text/csv");
}
