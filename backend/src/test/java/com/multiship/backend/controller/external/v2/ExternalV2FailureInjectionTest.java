package com.multiship.backend.controller.external.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.multiship.backend.config.ApiKeyPrincipal;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.ManifestRequestDTO;
import com.multiship.backend.dto.PickupRequestDTO;
import com.multiship.backend.dto.external.ExternalShipmentRequest;
import com.multiship.backend.dto.external.ExternalShipmentResponse;
import com.multiship.backend.service.LandedCostService;
import com.multiship.backend.service.ManifestService;
import com.multiship.backend.service.PickupService;
import com.multiship.backend.service.RateShopService;
import com.multiship.backend.service.external.ExternalApiService;
import com.multiship.backend.service.external.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 AC-M8 — failure-injection tests for the money-touching v2
 * endpoints. Verify that when {@link IdempotencyService} surfaces a
 * Redis outage on a fail-closed call, the controller emits 503 with
 * {@code IDEMPOTENCY_UNAVAILABLE}. This is the wire contract clients
 * branch on to distinguish "carrier said no" from "our idempotency
 * store is broken; please retry later".
 *
 * <p>Complements {@link ExternalV2ControllerTest}, which covers the
 * happy path (Redis absent → passthrough).
 */
class ExternalV2FailureInjectionTest {

    private ExternalApiService externalApiService;
    private RateShopService rateShopService;
    private PickupService pickupService;
    private ManifestService manifestService;
    private LandedCostService landedCostService;
    private IdempotencyService idempotency;
    private ExternalV2Controller controller;

    @BeforeEach
    void setUp() {
        externalApiService = mock(ExternalApiService.class);
        rateShopService = mock(RateShopService.class);
        pickupService = mock(PickupService.class);
        manifestService = mock(ManifestService.class);
        landedCostService = mock(LandedCostService.class);
        idempotency = mock(IdempotencyService.class);
        controller = new ExternalV2Controller(
                externalApiService, rateShopService, pickupService, manifestService,
                landedCostService, idempotency);
    }

    @Test
    void createShipment_passesFailClosedTrue_soRedisOutageSurfacesAs503() {
        // Simulate the "Redis down + failClosedOnRedisError=true" path
        // by having the mock IdempotencyService return the 503 body itself
        // (that's what the real service does — see redisUnavailableResponse).
        when(idempotency.executeOrReplay(org.mockito.ArgumentMatchers.anyLong(), any(), any(TypeReference.class), any(), eq(true)))
                .thenReturn(unavailable());

        ResponseEntity<ApiResponse<ExternalShipmentResponse>> resp =
                controller.create(new ExternalShipmentRequest(), caller(), "req-abc");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.IDEMPOTENCY_UNAVAILABLE.name(), resp.getBody().getErrorCode());
        // Assert the controller explicitly requested fail-closed semantics
        // on a money-touching endpoint (belt-and-braces for AC-M8).
        verify(idempotency).executeOrReplay(org.mockito.ArgumentMatchers.anyLong(), any(), any(TypeReference.class), any(), eq(true));
    }

    @Test
    void voidShipment_passesFailClosedTrue_soRedisOutageSurfacesAs503() {
        when(idempotency.executeOrReplay(org.mockito.ArgumentMatchers.anyLong(), any(), any(TypeReference.class), any(), eq(true)))
                .thenReturn(unavailable());

        ResponseEntity<ApiResponse<Map<String, Object>>> resp =
                controller.voidShipment(9L, caller(), "req-abc");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(ErrorCode.IDEMPOTENCY_UNAVAILABLE.name(), resp.getBody().getErrorCode());
        verify(idempotency).executeOrReplay(org.mockito.ArgumentMatchers.anyLong(), any(), any(TypeReference.class), any(), eq(true));
    }

    @Test
    void schedulePickup_passesFailClosedTrue() {
        when(idempotency.executeOrReplay(org.mockito.ArgumentMatchers.anyLong(), any(), any(TypeReference.class), any(), eq(true)))
                .thenReturn(unavailable());

        ResponseEntity<?> resp = controller.schedulePickup(new PickupRequestDTO(), caller(), "req-abc");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        verify(idempotency).executeOrReplay(org.mockito.ArgumentMatchers.anyLong(), any(), any(TypeReference.class), any(), eq(true));
    }

    @Test
    void closeOut_passesFailClosedTrue() {
        when(idempotency.executeOrReplay(org.mockito.ArgumentMatchers.anyLong(), any(), any(TypeReference.class), any(), eq(true)))
                .thenReturn(unavailable());

        ResponseEntity<?> resp = controller.closeOut(new ManifestRequestDTO(), caller(), "req-abc");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        verify(idempotency).executeOrReplay(org.mockito.ArgumentMatchers.anyLong(), any(), any(TypeReference.class), any(), eq(true));
    }

    // ===== helpers =====

    @SuppressWarnings("unchecked")
    private static <T> ResponseEntity unavailable() {
        ApiResponse<T> body = ApiResponse.<T>builder()
                .status("ERROR")
                .code(HttpStatus.SERVICE_UNAVAILABLE.value())
                .message("The idempotency store is unavailable.")
                .errorCode(ErrorCode.IDEMPOTENCY_UNAVAILABLE.name())
                .build();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(body);
    }

    private static ApiKeyPrincipal caller() {
        return new ApiKeyPrincipal(1L, "test-key", "ARHDEV", Set.of("*"));
    }
}
