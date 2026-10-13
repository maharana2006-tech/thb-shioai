package com.multiship.backend.service.dtc.scheduler;

import com.multiship.backend.service.oracle.OracleDtcSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * DTC_SYNC — pulls pending DTC orders from Oracle WMS for every tenant.
 * When the {@code nds-default} connection is inactive in Settings → External
 * Systems the run is recorded as skipped rather than failing.
 */
@Component
@RequiredArgsConstructor
public class DtcSyncJobRunner implements DtcSchedulerJobRunner {

    public static final String KEY = "DTC_SYNC";

    private final OracleDtcSyncService oracleDtcSyncService;

    @Override
    public String jobKey() {
        return KEY;
    }

    @Override
    public Outcome run(Map<String, Object> params) {
        if (!oracleDtcSyncService.isEnabled()) {
            return Outcome.skipped(OracleDtcSyncService.DISABLED_MESSAGE);
        }
        OracleDtcSyncService.OracleSyncResult result = oracleDtcSyncService.syncPendingDtcOrders("");  // all tenants
        // TODO: enqueue label generation for the newly synced batch
        //       (DtcLabelGenerationService.enqueue) to complete "sync and labels".
        return Outcome.success(String.format("Fetched %d, imported %d, skipped %d.%s",
                result.fetched, result.imported, result.skipped,
                result.message == null || result.message.isBlank() ? "" : " " + result.message));
    }
}
