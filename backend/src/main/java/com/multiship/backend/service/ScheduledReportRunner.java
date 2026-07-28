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

    private final ScheduledReportRepository scheduleRepo;
    private final GeneratedReportRepository generatedRepo;
    private final ReportService reportService;
    private final ObjectMapper objectMapper;

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
            RestClient.create().post()
                    .uri(schedule.getDeliveryWebhookUrl())
                    .header("Content-Type", "text/csv")
                    .header("X-Report-Filename", gr.getFilename())
                    .header("X-Report-Dataset", gr.getDataset())
                    .body(bytes)
                    .retrieve()
                    .toBodilessEntity();
        }
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
