package com.multiship.backend.service.carriers;

import com.multiship.backend.config.CarrierProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Sprint 49 Tier 2 — copy configured timeouts from
 * {@link CarrierProperties} into {@link HttpClients} on startup.
 *
 * <p>{@link HttpClients} is a static utility (deliberately no Spring
 * dep so the 163 direct-construction tests don't break); this thin
 * bean bridges Spring config to it at boot.
 *
 * <p>Defaults live in HttpClients (5s / 30s). Only overrides them if
 * the operator set both properties.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpClientsInitializer {

    private final CarrierProperties carrierProperties;

    @PostConstruct
    public void init() {
        Integer connect = carrierProperties.getConnectionTimeoutSeconds();
        Integer read = carrierProperties.getReadTimeoutSeconds();
        if (connect != null && read != null) {
            HttpClients.reconfigure(connect, read);
            log.info("HttpClients reconfigured — connect={}s, read={}s (from carrier.*-timeout-seconds).",
                    connect, read);
        }
    }
}
