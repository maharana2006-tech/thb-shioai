package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.dto.UspsFallbackAlertDTO;
import com.multiship.backend.model.UspsLabelQueueItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * PR-G3b - in-memory ring buffer of USPS_DIRECT silent-fallback alerts.
 *
 * <p>PR-G3a landed the WARN-level log for background-worker USPS_DIRECT
 * fallbacks (audit finding M-B3). Logs don't reach the ops dashboard;
 * this service adds a bounded ring buffer so
 * {@code GET /admin/usps-direct/dashboard/fallback-alerts} can surface
 * recent alerts to the FE for a banner render.
 *
 * <p>Bounded to {@link #maxAlerts} (default 50): recording the 51st
 * alert evicts the oldest. Losing history on a restart is acceptable
 * because:
 * <ul>
 *   <li>the log file remains the durable record;</li>
 *   <li>the alerts are actionable telemetry, not audit history;</li>
 *   <li>persistence would need its own migration + retention policy,
 *       which is out-of-scope for a "make this visible" fix.</li>
 * </ul>
 *
 * <p>Thread-safe via synchronised access on the deque - the recorder
 * call sites are per-order code paths (low contention) and the read
 * path (dashboard poll) is once every 30s per operator, so lock
 * granularity is not a hotspot.
 */
@Slf4j
@Service
public class UspsFallbackAlertService {

    private final int maxAlerts;
    private final Deque<UspsFallbackAlertDTO> alerts;

    public UspsFallbackAlertService(
            @Value("${usps.direct.queue.fallback-alerts.max:50}") int maxAlerts) {
        // Belt-and-braces - never let a mis-config leak a non-positive
        // buffer size (would break the eviction loop below).
        this.maxAlerts = maxAlerts <= 0 ? 50 : maxAlerts;
        this.alerts = new ArrayDeque<>(this.maxAlerts);
    }

    /**
     * Record a fallback event. Callers should invoke this alongside a
     * WARN-level log so both the log file AND the dashboard surface see
     * the event.
     *
     * <p>All parameters nullable except {@code reason}; the DTO carries
     * nulls verbatim so the FE can render "unknown tenant" / "no order"
     * gracefully when the fallback fired before those fields resolved.
     *
     * @param orderNo         order the fallback happened on (may be null)
     * @param tenantCode      tenant / client code (may be null)
     * @param importBatchId   import batch id when the fallback fired
     *                        inside an import context (may be null for
     *                        manual / bulk-label paths)
     * @param source          which surface caused the fallback; typically
     *                        {@link UspsLabelQueueItem.SourceType#IMPORT_BACKGROUND}
     *                        because that's where PR-G3a's WARN fires
     * @param reason          non-null short human-facing reason
     */
    public void record(Long orderNo,
                       String tenantCode,
                       Long importBatchId,
                       UspsLabelQueueItem.SourceType source,
                       String reason) {
        if (reason == null || reason.isBlank()) {
            // Silently no-op rather than throw - the recorder call sites
            // never want to leak an alert-recording bug into the carrier
            // path they're guarding.
            return;
        }
        UspsFallbackAlertDTO alert = UspsFallbackAlertDTO.builder()
                .occurredAt(Instant.now())
                .orderNo(orderNo)
                .tenantCode(tenantCode)
                .importBatchId(importBatchId)
                .reason(reason)
                .source(source == null ? "UNKNOWN" : source.name())
                .build();
        synchronized (alerts) {
            // Evict the oldest first so a burst of alerts doesn't grow
            // the deque unbounded. The offerFirst below then plants the
            // new alert at the head so recent-first iteration is cheap.
            while (alerts.size() >= maxAlerts) {
                alerts.pollLast();
            }
            alerts.offerFirst(alert);
        }
    }

    /**
     * PR-S4 — String-typed source overload so non-USPS carriers (Stamps
     * SERA fallback) can post into the same ring buffer without inventing
     * a new SourceType enum entry. The DTO's source field is already
     * String-typed (see build path above), so callers can pass any
     * carrier-scoped label — {@code "STAMPS_SERA_FALLBACK"},
     * {@code "STAMPS_SWSIM_401"} etc. — and the /dashboard/fallback-alerts
     * endpoint surfaces them uniformly alongside USPS_DIRECT alerts.
     */
    public void record(Long orderNo,
                       String tenantCode,
                       Long importBatchId,
                       String sourceLabel,
                       String reason) {
        if (reason == null || reason.isBlank()) return;
        UspsFallbackAlertDTO alert = UspsFallbackAlertDTO.builder()
                .occurredAt(Instant.now())
                .orderNo(orderNo)
                .tenantCode(tenantCode)
                .importBatchId(importBatchId)
                .reason(reason)
                .source(sourceLabel == null || sourceLabel.isBlank() ? "UNKNOWN" : sourceLabel)
                .build();
        synchronized (alerts) {
            while (alerts.size() >= maxAlerts) {
                alerts.pollLast();
            }
            alerts.offerFirst(alert);
        }
    }

    /**
     * Snapshot the current ring buffer, most-recent first. Returns an
     * immutable copy so callers can't accidentally mutate the shared
     * deque under the synchronised lock.
     */
    public List<UspsFallbackAlertDTO> recentAlerts() {
        synchronized (alerts) {
            return List.copyOf(new ArrayList<>(alerts));
        }
    }

    /** Test hook: clear the buffer between suites. */
    public void clearForTest() {
        synchronized (alerts) {
            alerts.clear();
        }
    }

    /** Test hook: max buffer size (constructor param echo). */
    public int maxAlerts() {
        return maxAlerts;
    }
}
