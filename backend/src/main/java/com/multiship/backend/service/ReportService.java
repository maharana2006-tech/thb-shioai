package com.multiship.backend.service;

import com.multiship.backend.dto.ReportFilters;

import java.io.OutputStream;

/**
 * Sprint 45 — reporting API. Streams CSV for each of the four
 * datasets. Callers pipe an HTTP response OutputStream (or the
 * scheduled-report runner pipes a ByteArrayOutputStream) so nothing
 * buffers a full result set in memory.
 */
public interface ReportService {

    /** Row per order/label: orderNo, client, ship date, carrier, service, tracking, cost, currency, dest country, weight, status. */
    void streamOrdersCsv(ReportFilters filters, OutputStream out);

    /** Row per tracking event: orderNo, event timestamp, status, location, description. */
    void streamTrackingCsv(ReportFilters filters, OutputStream out);

    /** Row per rate-shop run: timestamp, client, dest, weight, cheapest carrier/service/amount, carriers queried. */
    void streamRateShopCsv(ReportFilters filters, OutputStream out);

    /** Aggregated per client + month: total labels, total spend, avg spend. */
    void streamBillingCsv(ReportFilters filters, OutputStream out);
}
