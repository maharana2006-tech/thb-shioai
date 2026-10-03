package com.multiship.backend.service.observability;

import com.multiship.backend.service.carriers.HttpClients;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * V114 wire — installs {@link CarrierApiLoggingInterceptor} on
 * {@link HttpClients} so every carrier HTTP round-trip persists to
 * {@code carrier_api_log}. Feature-flagged by
 * {@code multiship.observability.carrier-api-log.enabled} (default true);
 * flip to {@code false} to disable logging if the table ever outruns
 * retention (expected: ~1-10k rows/day in production depending on label
 * volume).
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CarrierApiLoggingConfig {

    private final CarrierApiLogService logService;

    @Value("${multiship.observability.carrier-api-log.enabled:true}")
    private boolean enabled;

    @PostConstruct
    void installInterceptor() {
        HttpClients.setGlobalInterceptor(new CarrierApiLoggingInterceptor(logService, enabled));
        log.info("carrier-api-log: HttpClients interceptor installed (enabled={})", enabled);
    }
}
