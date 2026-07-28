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
    @GetMapping(value = "/orders", produces = "text/csv")
    public void orders(HttpServletResponse response, ReportFiltersQuery q) throws IOException {
        setCsvHeaders(response, "orders");
        reportService.streamOrdersCsv(q.toFilters(), response.getOutputStream());
    }

    @Operation(summary = "Tracking snapshots dataset (CSV stream)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping(value = "/tracking", produces = "text/csv")
    public void tracking(HttpServletResponse response, ReportFiltersQuery q) throws IOException {
        setCsvHeaders(response, "tracking");
        reportService.streamTrackingCsv(q.toFilters(), response.getOutputStream());
    }

    @Operation(summary = "Rate-shop history dataset (CSV stream)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping(value = "/rate-shop", produces = "text/csv")
    public void rateShop(HttpServletResponse response, ReportFiltersQuery q) throws IOException {
        setCsvHeaders(response, "rate-shop");
        reportService.streamRateShopCsv(q.toFilters(), response.getOutputStream());
    }

    @Operation(summary = "Client billing rollup dataset (CSV stream)")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping(value = "/billing", produces = "text/csv")
    public void billing(HttpServletResponse response, ReportFiltersQuery q) throws IOException {
        setCsvHeaders(response, "billing");
        reportService.streamBillingCsv(q.toFilters(), response.getOutputStream());
    }

    private static void setCsvHeaders(HttpServletResponse response, String dataset) {
        response.setContentType("text/csv");
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
            return ReportFilters.builder()
                    .from(parseDate(from))
                    .to(parseDate(to))
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

        private static LocalDateTime parseDate(String s) {
            if (s == null || s.isBlank()) return null;
            // Accept yyyy-MM-dd (start-of-day) or ISO datetime.
            if (s.length() == 10) return LocalDate.parse(s).atStartOfDay();
            return LocalDateTime.parse(s);
        }
    }

    // Also unused fields exposed on MediaType for consistency
    @SuppressWarnings("unused")
    private static final MediaType TEXT_CSV = MediaType.valueOf("text/csv");
}
