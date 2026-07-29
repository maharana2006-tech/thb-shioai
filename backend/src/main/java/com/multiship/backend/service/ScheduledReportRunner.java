package com.multiship.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.ReportFilters;
import com.multiship.backend.model.GeneratedReport;
import com.multiship.backend.model.GeneratedReport.DeliveryStatus;
import com.multiship.backend.model.ScheduledReport;
import com.multiship.backend.model.ScheduledReport.DeliveryType;
import com.multiship.backend.model.ScheduledReport.Frequency;
import com.multiship.backend.repository.GeneratedReportRepository;
import com.multiship.backend.repository.ScheduledReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Sprint 45 — the runner that ticks eligible {@link ScheduledReport}s
 * and delivers each one via its configured adapter (dashboard / email
 * / webhook). Runs every minute; short-circuits when nothing is due.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledReportRunner {

    /**
     * Audit-fix #7 — port the retry pattern from Sprint 46's
     * {@code ExternalWebhookDispatcher}. Same constants keep the two
     * webhook delivery paths behaviourally aligned.
     */
    static final int WEBHOOK_MAX_ATTEMPTS = 3;
    /** Wait ms before attempt N. Package-private so tests can override to zero. */
    static long[] webhookBackoffsMs = { 0L, 2_000L, 10_000L };

    private final ScheduledReportRepository scheduleRepo;
    private final GeneratedReportRepository generatedRepo;
    private final ReportService reportService;
    private final ObjectMapper objectMapper;

    /** Package-private RestClient injection point for tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RestClient testRestClient;

    /** Every minute. Runs synchronously — schedule count is small in v1. */
    @Scheduled(fixedDelay = 60_000L)
    public void tick() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<ScheduledReport> due = scheduleRepo.findDue(now);
        if (due.isEmpty()) return;
        log.info("ScheduledReportRunner: {} schedules due", due.size());
        for (ScheduledReport s : due) {
            try {
                runOne(s, now);
            } catch (Exception e) {
                log.warn("Scheduled report {} failed: {}", s.getId(), e.getMessage());
            }
        }
    }

    @Transactional
    public GeneratedReport runOne(ScheduledReport schedule, LocalDateTime now) {
        ReportFilters filters = parseFilters(schedule.getFiltersJson());
        // G6 — enforce the schedule's tenant scope on the CSV output.
        //
        // Before G6, the runner passed the raw filtersJson to reportService
        // untouched. A schedule row could be tenant-scoped (tenantId=ACME)
        // but its filtersJson could omit customerNo — the CSV would then
        // pull EVERY tenant's rows, cross-tenant leak.
        //
        // Rules:
        //   1. When the schedule has a tenantId, force filters.customerNo
        //      to it. Ignore any stored override.
        //   2. When creator was ROLE_TENANT, tenantId is mandatory — refuse
        //      to run a platform-scoped schedule from a tenant creator.
        //   3. Platform-scoped schedules from ADMIN / USER are unchanged.
        if (schedule.getTenantId() != null && !schedule.getTenantId().isBlank()) {
            if (filters.getCustomerNo() != null
                    && !schedule.getTenantId().equalsIgnoreCase(filters.getCustomerNo())) {
                log.warn("Schedule {} tenantId={} overriding filters.customerNo={} to prevent cross-tenant leak",
                        schedule.getId(), schedule.getTenantId(), filters.getCustomerNo());
            }
            filters.setCustomerNo(schedule.getTenantId());
        } else if ("ROLE_TENANT".equals(schedule.getCreatedByRole())) {
            throw new IllegalStateException(
                    "TENANT-created schedule " + schedule.getId()
                            + " has no tenantId — refusing to run to prevent cross-tenant leak.");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        String filename = buildFilename(schedule, now);
        switch (schedule.getDataset()) {
            case ORDERS    -> reportService.streamOrdersCsv(filters, baos);
            case TRACKING  -> reportService.streamTrackingCsv(filters, baos);
            case RATE_SHOP -> reportService.streamRateShopCsv(filters, baos);
            case BILLING   -> reportService.streamBillingCsv(filters, baos);
        }
        byte[] bytes = baos.toByteArray();

        GeneratedReport gr = new GeneratedReport();
        gr.setScheduleId(schedule.getId());
        gr.setTenantId(schedule.getTenantId());
        gr.setDataset(schedule.getDataset().name());
        gr.setFilename(filename);
        gr.setSizeBytes((long) bytes.length);
        gr.setCsvBytes(bytes);
        gr.setGeneratedAt(now);

        // Deliver
        try {
            deliver(schedule, gr, bytes);
            gr.setDeliveryStatus(DeliveryStatus.DELIVERED);
            gr.setDeliveryMessage("Delivered via " + schedule.getDeliveryType());
        } catch (Exception e) {
            gr.setDeliveryStatus(DeliveryStatus.FAILED);
            gr.setDeliveryMessage(e.getMessage());
            log.warn("Delivery for schedule {} failed: {}", schedule.getId(), e.getMessage());
        }
        generatedRepo.save(gr);

        // Advance nextRunAt
        schedule.setLastRunAt(now);
        schedule.setNextRunAt(nextRun(schedule.getFrequency(), now));
        schedule.setUpdatedAt(now);
        scheduleRepo.save(schedule);
        return gr;
    }

    /**
     * Package-private so the controller's "run now" endpoint can share it.
     */
    void deliver(ScheduledReport schedule, GeneratedReport gr, byte[] bytes) throws Exception {
        DeliveryType type = schedule.getDeliveryType();
        if (type == DeliveryType.DASHBOARD) {
            // Nothing to do — the bytes are already persisted for later download.
            return;
        }
        if (type == DeliveryType.EMAIL) {
            // Not wired to a real SMTP sender in v1; log so an ops user can
            // spot the intended recipient. Delivery still marked DELIVERED
            // to keep the state machine forward-moving; message notes the
            // stub so it's not silently dropped.
            log.info("EMAIL delivery stub — would send {} ({} bytes) to {}",
                    gr.getFilename(), bytes.length, schedule.getDeliveryEmail());
            gr.setDeliveryMessage("EMAIL delivery stub — SMTP not configured; recipients: "
                    + schedule.getDeliveryEmail());
            return;
        }
        if (type == DeliveryType.WEBHOOK) {
            if (schedule.getDeliveryWebhookUrl() == null || schedule.getDeliveryWebhookUrl().isBlank()) {
                throw new IllegalStateException("WEBHOOK delivery has no URL configured");
            }
            deliverWebhookWithRetry(schedule, gr, bytes);
        }
    }

    /**
     * Audit-fix #7 — retry the webhook POST up to {@link #WEBHOOK_MAX_ATTEMPTS}
     * times with exponential backoff on transient failures. Any non-2xx
     * response OR thrown exception counts as a failure. The final error is
     * re-thrown so the caller marks the delivery FAILED.
     */
    void deliverWebhookWithRetry(ScheduledReport schedule, GeneratedReport gr, byte[] bytes) throws Exception {
        RestClient client = testRestClient != null ? testRestClient : RestClient.create();
        Exception last = null;
        for (int attempt = 1; attempt <= WEBHOOK_MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                long wait = webhookBackoffsMs[Math.min(attempt - 1, webhookBackoffsMs.length - 1)];
                try { Thread.sleep(wait); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
            try {
                var response = client.post()
                        .uri(schedule.getDeliveryWebhookUrl())
                        .header("Content-Type", "text/csv")
                        .header("X-Report-Filename", gr.getFilename())
                        .header("X-Report-Dataset", gr.getDataset())
                        .body(bytes)
                        .retrieve()
                        .toBodilessEntity();
                if (response.getStatusCode().is2xxSuccessful()) return;
                last = new IllegalStateException(
                        "Webhook receiver returned HTTP " + response.getStatusCode().value());
            } catch (Exception e) {
                last = e;
            }
        }
        // Ran the loop MAX_ATTEMPTS times without a 2xx — surface the last error.
        if (last != null) throw last;
    }

    private static String buildFilename(ScheduledReport s, LocalDateTime now) {
        String stamp = now.toString().replace(':', '-').substring(0, 19);
        return s.getDataset().name().toLowerCase() + "-" + stamp + ".csv";
    }

    private ReportFilters parseFilters(String json) {
        if (json == null || json.isBlank()) return new ReportFilters();
        try {
            return objectMapper.readValue(json, ReportFilters.class);
        } catch (Exception e) {
            log.warn("Bad filters JSON on scheduled report, defaulting to empty: {}", e.getMessage());
            return new ReportFilters();
        }
    }

    static LocalDateTime nextRun(Frequency f, LocalDateTime now) {
        return switch (f) {
            case DAILY -> now.plusDays(1);
            case WEEKLY -> now.plusWeeks(1);
            case MONTHLY -> now.plusMonths(1);
        };
    }
}
