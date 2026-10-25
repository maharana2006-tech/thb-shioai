package com.multiship.backend.service.ndsshipment;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * D3 — Actuator health probe for {@link NdsShipmentOracleWriter}. Before
 * this indicator, a bean-construction failure silently activated the
 * "skipped: nds-writer bean unavailable" branch in {@code NdsOracleConnector},
 * turning every writeback into a no-op that only surfaced in logs at the
 * TRACE-ish level. Now:
 *
 * <ul>
 *   <li>{@code /actuator/health/ndsWriter} reports DOWN with a reason
 *       string when the writer bean can't be resolved.</li>
 *   <li>A one-shot ERROR log fires at boot when the writer is unavailable
 *       so the alert lands before the first shipment tries to write back.</li>
 * </ul>
 *
 * <p>Zero-configuration: with the writer bean present (normal prod state)
 * this reports UP with no detail — no operator noise for the happy path.
 */
@Slf4j
@Component("ndsWriter")
public class NdsWriterHealthIndicator implements HealthIndicator {

    private final ObjectProvider<NdsShipmentOracleWriter> writerProvider;
    /** BUG-005 — the writer can't commit when the Oracle DataSource is
     *  OUT_OF_SERVICE regardless of whether the bean is wired, so the
     *  "ndsWriter: UP while oracleDataSource: OUT_OF_SERVICE" split was
     *  misleading operators on dashboards. required=false because
     *  tenants without an NDS connection don't register the DataSource;
     *  there the bean is also absent so the writer-is-null branch
     *  fires first. */
    private final DataSource oracleDataSource;

    public NdsWriterHealthIndicator(
            ObjectProvider<NdsShipmentOracleWriter> writerProvider,
            @Qualifier("oracleDataSource") org.springframework.beans.factory.ObjectProvider<DataSource> oracleDsProvider) {
        this.writerProvider = writerProvider;
        this.oracleDataSource = oracleDsProvider.getIfAvailable();
    }

    @PostConstruct
    void bootAlert() {
        if (writerProvider.getIfAvailable() == null) {
            log.error("NDS writer bean unavailable at boot — every NDS writeback will be "
                    + "silently skipped. Check NdsShipmentOracleWriter's dependencies "
                    + "(NdsTemplates + external_system_connection row for the NDS Oracle "
                    + "connection). /actuator/health/ndsWriter will report DOWN.");
        }
    }

    @Override
    public Health health() {
        if (writerProvider.getIfAvailable() == null) {
            return Health.down()
                    .withDetail("reason",
                            "NdsShipmentOracleWriter bean is not registered. Every NDS writeback "
                                    + "will be skipped until the bean's dependencies resolve.")
                    .build();
        }
        // BUG-005 — writer bean up but its Oracle DataSource is DOWN =
        // every writeback is about to fail at commit. Report DOWN
        // alongside the DS so dashboards don't claim NDS is healthy
        // while Oracle is OUT_OF_SERVICE.
        if (oracleDataSource != null) {
            try (var conn = oracleDataSource.getConnection()) {
                if (!conn.isValid(1)) {
                    return Health.down()
                            .withDetail("reason", "Oracle DataSource reported not-valid; writer can't commit.")
                            .build();
                }
            } catch (Exception probeFailure) {
                return Health.down()
                        .withDetail("reason",
                                "Oracle DataSource probe failed (" + probeFailure.getClass().getSimpleName()
                                        + "): " + probeFailure.getMessage())
                        .build();
            }
        }
        return Health.up().build();
    }
}
