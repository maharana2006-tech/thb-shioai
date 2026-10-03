package com.multiship.backend.service.ndsshipment;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

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
@RequiredArgsConstructor
public class NdsWriterHealthIndicator implements HealthIndicator {

    private final ObjectProvider<NdsShipmentOracleWriter> writerProvider;

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
        if (writerProvider.getIfAvailable() != null) {
            return Health.up().build();
        }
        return Health.down()
                .withDetail("reason",
                        "NdsShipmentOracleWriter bean is not registered. Every NDS writeback "
                                + "will be skipped until the bean's dependencies resolve.")
                .build();
    }
}
