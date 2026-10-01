package com.multiship.backend.service.observability;

import com.multiship.backend.model.AlertHistoryEntity;
import com.multiship.backend.model.CarrierApiLogEntity;
import com.multiship.backend.repository.AlertHistoryRepository;
import com.multiship.backend.repository.CarrierApiLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** V113 + V114 — narrow writer behaviour: null source / url is a no-op;
 *  valid rows reach the repo; persist exceptions never escape. */
class ObservabilityServicesTest {

    @Test
    void alertHistoryRecordPersistsRow() {
        AlertHistoryRepository repo = mock(AlertHistoryRepository.class);
        new AlertHistoryService(repo).record("USPS_DIRECT", 42L, "ACME", 7L, "quota exhausted");
        ArgumentCaptor<AlertHistoryEntity> captor = ArgumentCaptor.forClass(AlertHistoryEntity.class);
        verify(repo).save(captor.capture());
        AlertHistoryEntity row = captor.getValue();
        assertEquals("USPS_DIRECT", row.getSource());
        assertEquals(Long.valueOf(42L), row.getTargetOrderNo());
        assertEquals("ACME", row.getTenantCode());
        assertEquals("quota exhausted", row.getReason());
        assertNotNull(row.getFiredAt());
    }

    @Test
    void alertHistoryRecordSkipsBlankSource() {
        AlertHistoryRepository repo = mock(AlertHistoryRepository.class);
        new AlertHistoryService(repo).record(null, 1L, "ACME", 0L, "x");
        new AlertHistoryService(repo).record("", 1L, "ACME", 0L, "x");
        verify(repo, never()).save(any());
    }

    @Test
    void alertHistorySwallowsRepoFailure() {
        AlertHistoryRepository repo = mock(AlertHistoryRepository.class);
        when(repo.save(any())).thenThrow(new RuntimeException("DB down"));
        // Must not propagate — in-memory ring buffer remains fallback.
        new AlertHistoryService(repo).record("USPS", null, null, null, "test");
    }

    @Test
    void carrierApiLogRecordPersistsRow() {
        CarrierApiLogRepository repo = mock(CarrierApiLogRepository.class);
        new CarrierApiLogService(repo).record("FEDEX", "POST",
                "https://apis.fedex.com/ship/v1/shipments",
                "{\"orderNo\":42}", "{\"trackingNumber\":\"1Z\"}",
                200, 350, null, 42L, "1Z999", "req-abc");
        ArgumentCaptor<CarrierApiLogEntity> captor = ArgumentCaptor.forClass(CarrierApiLogEntity.class);
        verify(repo).save(captor.capture());
        CarrierApiLogEntity row = captor.getValue();
        assertEquals("FEDEX", row.getCarrier());
        assertEquals("POST", row.getMethod());
        assertEquals(Integer.valueOf(200), row.getStatusCode());
        assertEquals("req-abc", row.getRequestId());
        assertEquals("1Z999", row.getTracking());
    }

    @Test
    void carrierApiLogSkipsBlankCarrierOrUrl() {
        CarrierApiLogRepository repo = mock(CarrierApiLogRepository.class);
        CarrierApiLogService svc = new CarrierApiLogService(repo);
        svc.record(null,  "GET", "http://x", null, null, 200, 1, null, null, null, null);
        svc.record("UPS", "GET", null,       null, null, 200, 1, null, null, null, null);
        verify(repo, never()).save(any());
    }

    @Test
    void carrierApiLogClampsOversizedBodies() {
        CarrierApiLogRepository repo = mock(CarrierApiLogRepository.class);
        String huge = "x".repeat(80_000);
        new CarrierApiLogService(repo).record("UPS", "POST", "http://x",
                huge, huge, 500, 1, "boom", null, null, null);
        ArgumentCaptor<CarrierApiLogEntity> captor = ArgumentCaptor.forClass(CarrierApiLogEntity.class);
        verify(repo).save(captor.capture());
        assertTrue(captor.getValue().getRequestBody().length() <= 60_000);
        assertTrue(captor.getValue().getResponseBody().length() <= 60_000);
    }
}
