package com.multiship.backend.metrics;

import com.multiship.backend.model.PrinterScanAgent;
import com.multiship.backend.repository.PrinterScanAgentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR-Printer-P4b — sanity-check the {@code printer_scan_agent_last_seen_seconds}
 * gauge. Uses {@link SimpleMeterRegistry} instead of Prometheus so the
 * test doesn't need Actuator wiring.
 */
class PrinterScanAgentMetricsTest {

    private PrinterScanAgentRepository repo;
    private SimpleMeterRegistry registry;
    private PrinterScanAgentMetrics metrics;

    @BeforeEach
    void setUp() {
        repo = mock(PrinterScanAgentRepository.class);
        registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        // Simulate Spring's ObjectProvider.ifAvailable for symmetry (not used).
        doAnswer(inv -> {
            Consumer<Object> c = inv.getArgument(0);
            c.accept(registry);
            return null;
        }).when(provider).ifAvailable(any());
        metrics = new PrinterScanAgentMetrics(repo, provider);
        metrics.initGauge();
    }

    @Test
    void refresh_emitsOneRowPerActiveAgent_withCorrectLastSeenDelta() {
        LocalDateTime now = LocalDateTime.now();
        PrinterScanAgent fresh = agent("ACME", "warehouse-north", now.minusSeconds(3));
        PrinterScanAgent stale = agent("BETA", "warehouse-east", now.minusMinutes(2));
        when(repo.findByActiveTrueOrderByTenantCodeAscAgentIdAsc())
                .thenReturn(List.of(fresh, stale));

        metrics.refresh();

        List<Gauge> gauges = registry.find("printer.scan.agent.last_seen.seconds").gauges().stream().toList();
        assertThat(gauges).hasSize(2);

        Gauge acme = registry.find("printer.scan.agent.last_seen.seconds")
                .tag("tenant", "ACME").tag("agent", "warehouse-north").gauge();
        Gauge beta = registry.find("printer.scan.agent.last_seen.seconds")
                .tag("tenant", "BETA").tag("agent", "warehouse-east").gauge();

        assertThat(acme).isNotNull();
        assertThat(beta).isNotNull();
        // ± 2s slack for the clock advancing between agent-creation and refresh().
        assertThat(acme.value()).isBetween(3.0, 5.0);
        assertThat(beta.value()).isBetween(120.0, 122.0);
    }

    @Test
    void refresh_fallsBackToEnrolledAt_whenLastSeenNull() {
        LocalDateTime now = LocalDateTime.now();
        PrinterScanAgent unpolled = new PrinterScanAgent();
        unpolled.setTenantCode("ACME");
        unpolled.setAgentId("brand-new");
        unpolled.setActive(Boolean.TRUE);
        unpolled.setEnrolledAt(now.minusSeconds(7));
        unpolled.setLastSeenAt(null);
        when(repo.findByActiveTrueOrderByTenantCodeAscAgentIdAsc())
                .thenReturn(List.of(unpolled));

        metrics.refresh();

        Gauge g = registry.find("printer.scan.agent.last_seen.seconds")
                .tag("tenant", "ACME").tag("agent", "brand-new").gauge();
        assertThat(g).isNotNull();
        assertThat(g.value()).isBetween(7.0, 9.0);
    }

    @Test
    void refresh_dropsRevokedAgents_onNextIteration() {
        LocalDateTime now = LocalDateTime.now();
        PrinterScanAgent a = agent("ACME", "one", now.minusSeconds(3));
        PrinterScanAgent b = agent("ACME", "two", now.minusSeconds(3));
        when(repo.findByActiveTrueOrderByTenantCodeAscAgentIdAsc())
                .thenReturn(List.of(a, b));
        metrics.refresh();
        assertThat(registry.find("printer.scan.agent.last_seen.seconds").gauges()).hasSize(2);

        // Simulate "two" being revoked between refreshes.
        when(repo.findByActiveTrueOrderByTenantCodeAscAgentIdAsc()).thenReturn(List.of(a));
        metrics.refresh();

        List<Gauge> after = registry.find("printer.scan.agent.last_seen.seconds").gauges().stream().toList();
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getId().getTag("agent")).isEqualTo("one");
    }

    private static PrinterScanAgent agent(String tenant, String id, LocalDateTime lastSeen) {
        PrinterScanAgent a = new PrinterScanAgent();
        a.setTenantCode(tenant);
        a.setAgentId(id);
        a.setActive(Boolean.TRUE);
        a.setEnrolledAt(lastSeen.minusMinutes(30));
        a.setLastSeenAt(lastSeen);
        return a;
    }
}
