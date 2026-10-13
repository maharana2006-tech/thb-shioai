package com.multiship.backend.service.dtc.scheduler;

import com.multiship.backend.service.oracle.OracleDtcSyncService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DtcSyncJobRunnerTest {

    private final OracleDtcSyncService sync = mock(OracleDtcSyncService.class);
    private final DtcSyncJobRunner runner = new DtcSyncJobRunner(sync);

    @Test
    void skipsWhenNdsDefaultInactive() {
        when(sync.isEnabled()).thenReturn(false);

        DtcSchedulerJobRunner.Outcome out = runner.run(Map.of());

        assertEquals(DtcSchedulerEngine.SKIPPED, out.status());
        assertEquals(OracleDtcSyncService.DISABLED_MESSAGE, out.message());
        verify(sync, never()).syncPendingDtcOrders(anyString());
    }

    @Test
    void syncsAllTenantsWhenNdsDefaultActive() {
        when(sync.isEnabled()).thenReturn(true);
        when(sync.syncPendingDtcOrders(""))
                .thenReturn(new OracleDtcSyncService.OracleSyncResult(3, 2, 1, ""));

        DtcSchedulerJobRunner.Outcome out = runner.run(Map.of());

        assertEquals(DtcSchedulerEngine.SUCCESS, out.status());
        assertEquals("Fetched 3, imported 2, skipped 1.", out.message());
    }
}
