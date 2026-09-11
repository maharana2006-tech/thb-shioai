package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.OrderImportPreviewDTO;
import com.multiship.backend.dto.OrderImportRowDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-09-10 load test: UPS throttled from ~order 480 and 139 orders failed; an
 * immediate retry recovered none. A carrier 429 must now pause that carrier and
 * resend the throttled orders automatically after the cool-down.
 */
class OrderImportRateLimitTest {

    private CarrierService carrierService;
    private OrderImportServiceImpl service;

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        service = new OrderImportServiceImpl(carrierService);
        // Real cool-downs are 30 s .. 4 min; keep the test fast.
        ReflectionTestUtils.setField(service, "rateLimitWaitsMs", new long[]{40L, 40L, 40L, 40L});
    }

    private static OrderImportRowDTO row(int n) {
        return OrderImportRowDTO.builder()
                .rowNumber(n).orderRef("RL-" + n).clientCode("ACME")
                .recipientName("Jane " + n).recipientPhone("2125550100")
                .addressLine1(n + " Broadway").city("New York").state("NY").postalCode("10001").countryCode("US")
                .carrierCode("UPS").accountNumber("A12345")
                .weight(new BigDecimal("2.5")).weightUnit("LB")
                .build();
    }

    private static ApiResponse<LabelGenerationResponse> ok(long orderNo) {
        return ApiResponse.<LabelGenerationResponse>builder()
                .status("success").code(200).message("ok")
                .data(LabelGenerationResponse.builder().orderNo(orderNo).trackingNumber("TN-" + orderNo).status("GENERATED").build())
                .build();
    }

    private static ApiResponse<LabelGenerationResponse> throttled() {
        return ApiResponse.<LabelGenerationResponse>builder()
                .status("error").code(429).errorCode("CARRIER_RATE_LIMITED")
                .message("Carrier UPS is rate-limiting requests.")
                .build();
    }

    @Test
    void throttledOrdersAreResentAfterTheCoolDown() {
        AtomicInteger calls = new AtomicInteger();
        when(carrierService.generateManualLabel(any(), any(), any())).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            return n == 1 ? throttled() : ok(5000L + n);
        });
        List<String> notes = new ArrayList<>();

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(
                List.of(row(1), row(2), row(3)), "alice", false, null, null, null,
                n -> { if (n != null) synchronized (notes) { notes.add(n); } });

        for (OrderImportRowDTO r : resp.getData().getRows()) {
            assertEquals("GENERATED", r.getGeneratedStatus(), "row " + r.getRowNumber() + ": " + r.getGeneratedMessage());
        }
        assertTrue(calls.get() >= 4 && calls.get() <= 6, "one throttled call, then each order once more at most; got " + calls.get());
        assertTrue(notes.stream().anyMatch(n -> n.contains("asked us to slow down")), "progress note while waiting: " + notes);
    }

    @Test
    void aCarrierThatKeepsThrottlingStopsAfterTheRetryBudget() {
        AtomicInteger calls = new AtomicInteger();
        when(carrierService.generateManualLabel(any(), any(), any())).thenAnswer(inv -> {
            calls.incrementAndGet();
            return throttled();
        });

        ApiResponse<OrderImportPreviewDTO> resp = service.commit(List.of(row(1), row(2)), "alice", false, null, null, null, null);

        for (OrderImportRowDTO r : resp.getData().getRows()) {
            assertEquals("FAILED", r.getGeneratedStatus());
            assertTrue(r.getGeneratedMessage().contains("still rate-limiting after 4 automatic retries"), r.getGeneratedMessage());
        }
        assertTrue(calls.get() <= 2 * 5, "bounded: first pass + 4 retries per order at most; got " + calls.get());
    }
}
