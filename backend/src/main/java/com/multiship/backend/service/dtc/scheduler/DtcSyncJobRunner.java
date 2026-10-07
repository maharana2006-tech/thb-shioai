package com.multiship.backend.service.dtc.scheduler;

import com.multiship.backend.service.oracle.OracleDtcSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * DTC_SYNC — pulls pending DTC orders from Oracle WMS for every tenant.
 * {@link OracleDtcSyncService} only exists when multiship.oracle.enabled=true,
 * so without it the run is recorded as skipped rather than failing.
 */
@Component
@RequiredArgsConstructor
public class DtcSyncJobRunner implements DtcSchedulerJobRunner {

    public static final String KEY = "DTC_SYNC";

    private final ObjectProvider<OracleDtcSyncService> oracleDtcSyncService;

    @Override
    public String jobKey() {
        return KEY;
    }

    @Override
    public Outcome run(Map<String, Object> params) {
        OracleDtcSyncService sync = oracleDtcSyncService.getIfAvailable();
        if (sync == null) {
            return Outcome.skipped("Oracle is not enabled (multiship.oracle.enabled=false).");
        }
        OracleDtcSyncService.OracleSyncResult result = sync.syncPendingDtcOrders("");  // all tenants
        // TODO: enqueue label generation for the newly synced batch
        //       (DtcLabelGenerationService.enqueue) to complete "sync and labels".
        return Outcome.success(String.format("Fetched %d, imported %d, skipped %d.%s",
                result.fetched, result.imported, result.skipped,
                result.message == null || result.message.isBlank() ? "" : " " + result.message));
    }
}
