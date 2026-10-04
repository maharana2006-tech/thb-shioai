package com.multiship.backend.service.observability;

import com.multiship.backend.model.CarrierApiLogEntity;
import com.multiship.backend.repository.CarrierApiLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * V114 — narrow writer for the carrier_api_log table.
 *
 * <p>Wired globally via
 * {@link com.multiship.backend.service.observability.CarrierApiLoggingConfig}
 * (installs {@link CarrierApiLoggingInterceptor} on
 * {@code HttpClients.setGlobalInterceptor}). Every carrier that uses the
 * shared HTTP factory — FedEx, UPS, USPS, DHL, Stamps, USPS_Direct, plus
 * any future connector — logs each HTTP round-trip automatically.
 * Direct {@link #record} calls remain available for non-HTTP writes
 * (SOAP wrappers, synthetic traces) — bodies clamped to 60 kB.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CarrierApiLogService {

    private static final int BODY_MAX = 60_000;
    private static final int ERR_MAX = 4_000;

    private final CarrierApiLogRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String carrier, String method, String url,
                       String requestBody, String responseBody,
                       Integer statusCode, Integer latencyMs,
                       String errorMessage, Long orderNo, String tracking,
                       String requestId) {
        if (carrier == null || carrier.isBlank() || url == null) return;
        try {
            repo.save(CarrierApiLogEntity.builder()
                    .requestId(requestId)
                    .carrier(carrier)
                    .method(method == null ? "?" : method)
                    .url(url)
                    .requestBody(clamp(requestBody, BODY_MAX))
                    .responseBody(clamp(responseBody, BODY_MAX))
                    .statusCode(statusCode)
                    .latencyMs(latencyMs)
                    .errorMessage(clamp(errorMessage, ERR_MAX))
                    .orderNo(orderNo)
                    .tracking(tracking)
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                    .build());
        } catch (Exception ex) {
            log.warn("carrier-api-log: persist failed for carrier={} url={}: {}",
                    carrier, url, ex.getMessage());
        }
    }

    private static String clamp(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
