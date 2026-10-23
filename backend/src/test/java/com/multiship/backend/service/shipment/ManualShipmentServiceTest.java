package com.multiship.backend.service.shipment;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.dto.OrderResponseDTO;
import com.multiship.backend.model.ShipmentAttempt;
import com.multiship.backend.repository.ShipmentAttemptRepository;
import com.multiship.backend.service.CarrierService;
import com.multiship.backend.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 0 gate — a purchase is addressable by its attempt key: the same key
 * replays, a concurrent same key loses with 409, and the carrier is called
 * exactly once per key. The repository is a map standing in for the
 * primary-key semantics of shipment_attempt.
 */
class ManualShipmentServiceTest {

    private final Map<UUID, ShipmentAttempt> rows = new ConcurrentHashMap<>();
    private ShipmentAttemptRepository repo;
    private CarrierService carrier;
    private OrderService orders;
    private ManualShipmentService svc;
    private final UserDetails user = User.withUsername("admin").password("x").roles("ADMIN").build();

    @BeforeEach
    void setUp() {
        repo = mock(ShipmentAttemptRepository.class);
        when(repo.claim(any(), any(), anyString(), any(), anyString(), any())).thenAnswer(inv -> {
            UUID key = inv.getArgument(0);
            ShipmentAttempt row = ShipmentAttempt.builder().attemptKey(key).orderNo(inv.getArgument(1))
                    .state(ShipmentAttempt.PURCHASING).fingerprint(inv.getArgument(2)).actor(inv.getArgument(3))
                    .requestJson(inv.getArgument(4)).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
            return rows.putIfAbsent(key, row) == null ? 1 : 0;
        });
        when(repo.finish(any(), anyString(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            ShipmentAttempt row = rows.get((UUID) inv.getArgument(0));
            row.setState(inv.getArgument(1));
            if (inv.getArgument(2) != null) row.setOrderNo(inv.getArgument(2));
            row.setResponseJson(inv.getArgument(3));
            row.setErrorCode(inv.getArgument(4));
            row.setErrorMessage(inv.getArgument(5));
            return 1;
        });
        when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(rows.get((UUID) inv.getArgument(0))));
        when(repo.reclaim(any(), any())).thenAnswer(inv -> {
            ShipmentAttempt row = rows.get((UUID) inv.getArgument(0));
            if (row == null || !ShipmentAttempt.FAILED.equals(row.getState())) return 0;
            row.setState(ShipmentAttempt.PURCHASING);
            return 1;
        });
        carrier = mock(CarrierService.class);
        orders = mock(OrderService.class);
        svc = new ManualShipmentService(repo, carrier, orders);
    }

    private static ApiResponse<LabelGenerationResponse> generated(int orderNo) {
        LabelGenerationResponse data = new LabelGenerationResponse();
        data.setOrderNo((long) orderNo);
        data.setStatus("GENERATED");
        data.setTrackingNumber("1Z" + orderNo);
        return ApiResponse.<LabelGenerationResponse>builder().status("success").code(200)
                .message("Label generated successfully.").data(data).timestamp(LocalDateTime.now()).build();
    }

    @Test
    void same_key_twice_replays_the_stored_response_and_calls_the_carrier_once() {
        when(carrier.purchaseManualLabel(any(), any(), any())).thenReturn(generated(900100));
        String key = UUID.randomUUID().toString();
        ManualShipmentRequest req = new ManualShipmentRequest();

        ApiResponse<LabelGenerationResponse> first = svc.purchase(req, key, user, null);
        ApiResponse<LabelGenerationResponse> second = svc.purchase(req, key, user, null);

        assertEquals(200, first.getCode());
        assertEquals(900100L, second.getData().getOrderNo());
        assertEquals("1Z900100", second.getData().getTrackingNumber());
        verify(carrier, times(1)).purchaseManualLabel(any(), any(), any());
        assertEquals(ShipmentAttempt.DONE, rows.values().iterator().next().getState());
        verify(repo).stampOrder(900100, UUID.fromString(key));
    }

    @Test
    void concurrent_same_key_purchases_once_and_the_loser_gets_409() throws Exception {
        CountDownLatch inCarrier = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(carrier.purchaseManualLabel(any(), any(), any())).thenAnswer(inv -> {
            calls.incrementAndGet();
            inCarrier.countDown();
            release.await();
            return generated(900101);
        });
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ApiResponse<LabelGenerationResponse>> a = pool.submit(() -> svc.purchase(new ManualShipmentRequest(), key, user, null));
            assertTrue(inCarrier.await(5, java.util.concurrent.TimeUnit.SECONDS), "first call is inside the carrier");
            ApiResponse<LabelGenerationResponse> b = svc.purchase(new ManualShipmentRequest(), key, user, null);
            release.countDown();
            ApiResponse<LabelGenerationResponse> aResp = a.get();

            assertEquals(200, aResp.getCode());
            assertEquals(409, b.getCode());
            assertEquals(ErrorCode.ATTEMPT_IN_PROGRESS.name(), b.getErrorCode());
            assertEquals(1, calls.get(), "exactly one purchase");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void a_failed_purchase_is_recorded_and_the_same_key_may_retry_it() {
        ApiResponse<LabelGenerationResponse> rejected = ApiResponse.<LabelGenerationResponse>builder()
                .status("error").code(502).errorCode("CARRIER_FAILURE").message("STAMPS rejected").timestamp(LocalDateTime.now()).build();
        when(carrier.purchaseManualLabel(any(), any(), any())).thenReturn(rejected, generated(900103));
        String key = UUID.randomUUID().toString();

        ApiResponse<LabelGenerationResponse> first = svc.purchase(new ManualShipmentRequest(), key, user, null);
        ShipmentAttempt row = rows.values().iterator().next();
        assertEquals(502, first.getCode());
        assertEquals(ShipmentAttempt.FAILED, row.getState());
        assertEquals("STAMPS rejected", row.getErrorMessage());

        // Import / queue callers key by order, so a retry under the same key purchases again.
        ApiResponse<LabelGenerationResponse> again = svc.purchase(new ManualShipmentRequest(), key, user, null);

        assertEquals(200, again.getCode());
        assertEquals(ShipmentAttempt.DONE, row.getState());
        verify(carrier, times(2)).purchaseManualLabel(any(), any(), any());
    }

    @Test
    void a_partial_multi_piece_failure_is_recorded_as_partial() {
        LabelGenerationResponse data = new LabelGenerationResponse();
        data.setOrderNo(900104L);
        data.setStatus("PARTIAL");
        ApiResponse<LabelGenerationResponse> partial = ApiResponse.<LabelGenerationResponse>builder()
                .status("error").code(502).errorCode("CARRIER_FAILURE").message("piece 2 failed; NOT voided: 1Z1").data(data).build();
        when(carrier.purchaseManualLabel(any(), any(), any())).thenReturn(partial);

        svc.purchase(new ManualShipmentRequest(), UUID.randomUUID().toString(), user, null);

        assertEquals(ShipmentAttempt.PARTIAL, rows.values().iterator().next().getState());
    }

    @Test
    void reattempt_on_a_generated_order_is_refused_before_any_carrier_call() {
        OrderResponseDTO dto = OrderResponseDTO.builder()
                .labelDetails(OrderResponseDTO.LabelDetails.builder().isGenerated(true).status("GENERATED").build())
                .build();
        when(orders.getOrderWithTracking(900058)).thenReturn(ApiResponse.<OrderResponseDTO>builder().code(200).data(dto).build());

        ApiResponse<LabelGenerationResponse> r = svc.purchase(new ManualShipmentRequest(), UUID.randomUUID().toString(), user, 900058);

        assertEquals(409, r.getCode());
        assertEquals(ErrorCode.LABEL_ALREADY_GENERATED.name(), r.getErrorCode());
        verify(carrier, never()).purchaseManualLabel(any(), any(), any());
        assertEquals(ShipmentAttempt.FAILED, rows.values().iterator().next().getState());
    }

    @Test
    void a_blank_or_non_uuid_key_still_purchases_and_is_stable_per_text() {
        when(carrier.purchaseManualLabel(any(), any(), any())).thenReturn(generated(900102));
        assertEquals(200, svc.purchase(new ManualShipmentRequest(), "  ", user, null).getCode());
        assertEquals(200, svc.purchase(new ManualShipmentRequest(), null, user, null).getCode());
        assertEquals(ManualShipmentService.parseKey("gen-900102-1"), ManualShipmentService.parseKey("gen-900102-1"));
        assertNotEquals(ManualShipmentService.parseKey(""), ManualShipmentService.parseKey(""));
        verify(carrier, times(2)).purchaseManualLabel(any(), any(), any());
    }
}
