package com.multiship.backend.service.oracle;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Automated DTC Background Sync Service.
 * Mimics the ShipXSync DTCTimedBackgroundService behavior.
 *
 * Scheduling:
 *   - 06:00–11:59 (weekdays/weekends): Every 20 minutes
 *   - 12:00–21:59 (weekdays): Every 5 minutes  ← PEAK HOURS
 *   - 22:00–05:59: Every 30 minutes
 *   - 21:00: FedEx ETD upload (daily)
 *
 * Note: Spring @Scheduled has limited timezone support. For precise window-based
 * scheduling across timezones, consider using Quartz or an external scheduler.
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "multiship.oracle.enabled", havingValue = "true")
@RequiredArgsConstructor
@EnableScheduling
public class DtcTimedBackgroundService {

    private static final Logger log = LoggerFactory.getLogger(DtcTimedBackgroundService.class);

    private final OracleDtcSyncService oracleDtcSyncService;

    /**
     * Peak hours sync: Every 5 minutes during 12:00–21:59 (business hours).
     * Cron: 0 * /5 12-21 * * MON-FRI
     */
//    @Scheduled(cron = "0 */5 12-21 * * MON-FRI", zone = "UTC")
    public void syncDtcOrdersPeakHours() {
        log.info("[DTC Peak] Starting DTC sync (5-min interval)");
        try {
            OracleDtcSyncService.OracleSyncResult result =
                    oracleDtcSyncService.syncPendingDtcOrders("");  // all tenants
            log.info("[DTC Peak] Completed: {}", result);
        } catch (Exception e) {
            log.error("[DTC Peak] Sync failed", e);
        }
    }

    /**
     * Normal hours sync: Every 20 minutes during 06:00–11:59 (weekdays & weekends).
     * Cron: 0 * /20 6-11 * * *
     */
//    @Scheduled(cron = "0 */20 6-11 * * *", zone = "UTC")
    public void syncDtcOrdersNormalHours() {
        log.info("[DTC Normal] Starting DTC sync (20-min interval)");
        try {
            OracleDtcSyncService.OracleSyncResult result =
                    oracleDtcSyncService.syncPendingDtcOrders("");  // all tenants
            log.info("[DTC Normal] Completed: {}", result);
        } catch (Exception e) {
            log.error("[DTC Normal] Sync failed", e);
        }
    }

    /**
     * Off-hours sync: Every 30 minutes during 22:00–05:59 (low traffic).
     * Cron: 0 * /30 22-23,0-5 * * *
     */
//    @Scheduled(cron = "0 */30 22-23,0-5 * * *", zone = "UTC")
    public void syncDtcOrdersOffHours() {
        log.info("[DTC OffHours] Starting DTC sync (30-min interval)");
        try {
            OracleDtcSyncService.OracleSyncResult result =
                    oracleDtcSyncService.syncPendingDtcOrders("");  // all tenants
            log.info("[DTC OffHours] Completed: {}", result);
        } catch (Exception e) {
            log.error("[DTC OffHours] Sync failed", e);
        }
    }

    /**
     * Daily FedEx ETD (Electronic Trade Document) upload at 21:00 UTC.
     * Placeholder for FedEx commercial invoice upload logic.
     */
//    @Scheduled(cron = "0 0 21 * * *", zone = "UTC")
    public void syncFedexEtdDaily() {
        log.info("[DTC ETD] Daily FedEx ETD sync at 21:00");
        try {
            // TODO: Implement FedEx ETD upload logic
            log.info("[DTC ETD] FedEx ETD sync completed");
        } catch (Exception e) {
            log.error("[DTC ETD] ETD sync failed", e);
        }
    }

    /**
     * Manual trigger point for per-tenant sync (useful for debugging).
     * Call this from DTCController if Super Admin clicks "Start Sync" button.
     *
     * @param tenantId the tenant to sync (empty = all tenants)
     */
    public void manualSyncDtcOrders(String tenantId) {
        log.info("[DTC Manual] User-triggered DTC sync for tenant={}", tenantId);
        try {
            OracleDtcSyncService.OracleSyncResult result =
                    oracleDtcSyncService.syncPendingDtcOrders(tenantId);
            log.info("[DTC Manual] Completed: {}", result);
        } catch (Exception e) {
            log.error("[DTC Manual] Sync failed", e);
        }
    }
}
