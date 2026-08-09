package com.multiship.backend.service;

import com.multiship.backend.model.ApiKey;
import com.multiship.backend.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Sprint 50 Tier 0.5 PR C — nightly cron that warns about API keys nearing
 * expiry. Emits WARN log lines today; PR D wires the mailer so these
 * become real admin emails (design in the plan doc).
 *
 * <p>Fires at 02:07 UTC daily — arbitrary time offset from other nightly
 * jobs. Two windows: 7-day and 1-day. The 7-day query returns everything
 * expiring in (24h, 168h] so the 1-day window doesn't double-alert; the
 * 1-day query is the (0, 24h] slice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyExpiryWarner {

    private final ApiKeyRepository apiKeyRepository;

    @Scheduled(cron = "0 7 2 * * *")
    public void warnExpiring() {
        LocalDateTime now = LocalDateTime.now();
        warnWindow(now.plusHours(24), now.plusHours(168), "7 days");
        warnWindow(now, now.plusHours(24), "24 hours");
    }

    private void warnWindow(LocalDateTime from, LocalDateTime to, String label) {
        List<ApiKey> expiring = apiKeyRepository.findByActiveTrueAndExpiresAtBetween(from, to);
        if (expiring.isEmpty()) return;

        log.warn("[api-key-expiry] {} key(s) expire within {} — rotate via POST /api/v1/api-keys/{}/rotate",
                expiring.size(), label, "{id}");
        for (ApiKey key : expiring) {
            log.warn("[api-key-expiry] key id={} name='{}' client={} expiresAt={}",
                    key.getId(), key.getName(), key.getClientCode(), key.getExpiresAt());
        }
    }
}
