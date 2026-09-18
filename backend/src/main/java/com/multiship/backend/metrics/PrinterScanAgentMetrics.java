package com.multiship.backend.metrics;

import com.multiship.backend.model.PrinterScanAgent;
import com.multiship.backend.repository.PrinterScanAgentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * PR-Printer-P4b — publishes the "seconds since last agent poll" gauge
 * to Prometheus so ops can alert on stale scanners. Metric:
 *
 * <pre>
 * printer_scan_agent_last_seen_seconds{tenant="ACME",agent="warehouse-north"} 3.0
 * </pre>
 *
 * <p>Grafana alert (documented in {@code docs/printer-scan-agent-runbook.md} §8):
 * {@code max(printer_scan_agent_last_seen_seconds) by (tenant,agent) > 30}
 * fires when an agent hasn't polled in 30s. A healthy 5s poll interval
 * keeps this &lt; 10s under normal load; hitting 30s means the agent
 * container crashed, lost network, or its key was rotated without an
 * update to the container env.
 *
 * <p>Implementation: {@link MultiGauge} handles a dynamic set of
 * tag-combinations under a single gauge name. We re-populate the row
 * set every 15s from the DB — cheap query (index on active), and 15s
 * is well below the 30s alert threshold so a stale reading can't linger
 * long enough to mask a real outage.
 *
 * <p>Only active agents are emitted; a revoked agent stops appearing
 * in the metric (Prometheus staleness handles the graceful disappear).
 */
@Slf4j
@Component
public class PrinterScanAgentMetrics {

    /** How often to walk the DB + refresh the gauge rows. */
    static final long REFRESH_MS = 15_000L;

    private final PrinterScanAgentRepository agentRepository;
    /** ObjectProvider so unit tests without a MeterRegistry still boot. */
    private final ObjectProvider<MeterRegistry> registryProvider;
    private MultiGauge lastSeenGauge;

    public PrinterScanAgentMetrics(PrinterScanAgentRepository agentRepository,
                                   ObjectProvider<MeterRegistry> registryProvider) {
        this.agentRepository = agentRepository;
        this.registryProvider = registryProvider;
    }

    @PostConstruct
    void initGauge() {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            log.info("MeterRegistry not present — printer scan agent metrics disabled.");
            return;
        }
        this.lastSeenGauge = MultiGauge.builder("printer.scan.agent.last_seen.seconds")
                .description("Seconds since the agent's last successful poll of /printer-scan-agents/poll")
                .baseUnit("seconds")
                .register(registry);
    }

    /**
     * Repopulate the gauge from every active agent. Package-private so
     * tests can call directly (Spring's scheduler is off in the test
     * profile).
     */
    @Scheduled(fixedRate = REFRESH_MS, initialDelay = 30_000L)
    public void refresh() {
        if (lastSeenGauge == null) return;
        List<PrinterScanAgent> active = agentRepository.findByActiveTrueOrderByTenantCodeAscAgentIdAsc();
        LocalDateTime now = LocalDateTime.now();
        List<MultiGauge.Row<?>> rows = new ArrayList<>(active.size());
        for (PrinterScanAgent a : active) {
            LocalDateTime lastSeen = a.getLastSeenAt() != null ? a.getLastSeenAt() : a.getEnrolledAt();
            long secondsSince = lastSeen != null
                    ? Math.max(0L, ChronoUnit.SECONDS.between(lastSeen, now))
                    : 0L;
            rows.add(MultiGauge.Row.of(
                    Tags.of("tenant", nullSafe(a.getTenantCode()),
                            "agent", nullSafe(a.getAgentId())),
                    secondsSince));
        }
        // true = overwrite existing rows so revoked agents disappear.
        lastSeenGauge.register(rows, true);
    }

    private static String nullSafe(String s) {
        return s == null ? "unknown" : s;
    }
}
