package com.multiship.backend.service.observability;

import com.multiship.backend.repository.CarrierApiLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** BUG-014 — a manual shipment's carrier calls are stamped with the order once it exists. */
class CarrierApiLogTagRequestTest {

    @AfterEach
    void clearMdc() { MDC.clear(); }

    @Test
    void stampsTheCurrentRequestsCallsWithTheOrderAndTracking() {
        CarrierApiLogRepository repo = mock(CarrierApiLogRepository.class);
        CarrierApiLogService service = new CarrierApiLogService(repo);
        MDC.put("requestId", "req-1");

        service.tagCurrentRequest(900060L, "9434650899563003347255");

        verify(repo).tagRequest("req-1", 900060L, "9434650899563003347255");
    }

    @Test
    void outsideAWebRequestThereIsNothingToStamp() {
        CarrierApiLogRepository repo = mock(CarrierApiLogRepository.class);
        new CarrierApiLogService(repo).tagCurrentRequest(900060L, "x");
        verifyNoInteractions(repo);
    }
}
