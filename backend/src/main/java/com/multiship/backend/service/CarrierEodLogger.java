package com.multiship.backend.service;

import com.multiship.backend.model.CarrierEodLog;
import com.multiship.backend.repository.CarrierEodLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * V135 — single writer for the carrier pickup / end-of-day-close audit log
 * (used by both {@link PickupServiceImpl} and {@link ManifestServiceImpl}).
 * Never throws: an audit-log failure must not fail the pickup/close itself.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CarrierEodLogger {

    private final CarrierEodLogRepository repository;

    /** @param kind PICKUP | CLOSEOUT; @param source MANUAL | SCHEDULED. */
    public void record(String kind, String carrier, String accountNumber, String customerNo,
                       String warehouseCode, LocalDate eventDate, String reference, int count,
                       String status, String message, String source) {
        try {
            repository.save(CarrierEodLog.builder()
                    .kind(kind)
                    .carrierCode(carrier == null ? null : carrier.trim().toUpperCase(Locale.ROOT))
                    .accountNumber(accountNumber)
                    .customerNo(StringUtils.hasText(customerNo) ? customerNo.trim() : null)
                    .warehouseCode(StringUtils.hasText(warehouseCode) ? warehouseCode.trim() : null)
                    .eventDate(eventDate)
                    .reference(reference)
                    .trackingCount(Math.max(count, 0))
                    .status(StringUtils.hasText(status) ? status : "ERROR")
                    .message(message == null ? null : message.substring(0, Math.min(message.length(), 1000)))
                    .source(StringUtils.hasText(source) ? source : "MANUAL")
                    .createdBy(currentUsername())
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception ex) {
            log.warn("Could not write carrier_eod_log ({} {}): {}", kind, carrier, ex.toString());
        }
    }

    private static String currentUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return auth == null ? "system" : auth.getName();
        } catch (Exception e) {
            return "system";
        }
    }
}
