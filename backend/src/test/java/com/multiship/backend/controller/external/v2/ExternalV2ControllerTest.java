package com.multiship.backend.controller.external.v2;

import com.multiship.backend.config.ApiKeyPrincipal;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LandedCostRequestDTO;
import com.multiship.backend.dto.LandedCostResponseDTO;
import com.multiship.backend.dto.ManifestRequestDTO;
import com.multiship.backend.dto.ManifestResponseDTO;
import com.multiship.backend.dto.PickupRequestDTO;
import com.multiship.backend.dto.PickupResponseDTO;
import com.multiship.backend.dto.RateShopRequestDTO;
import com.multiship.backend.dto.RateShopResponseDTO;
import com.multiship.backend.service.LandedCostService;
import com.multiship.backend.service.ManifestService;
import com.multiship.backend.service.PickupService;
import com.multiship.backend.service.RateShopService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.service.external.ExternalApiService;
import com.multiship.backend.service.external.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Audit-fix #1 — the four v2 delegate endpoints (rate-shop, pickups,
 * close-out, landed-cost) invoked their underlying service TWICE per
 * call (once for the HTTP status, once for the body). Every external
 * request cost double the carrier API quota + double the latency.
 *
 * <p>Each test below verifies the service is called <b>exactly once</b>
 * per controller invocation.
 */
class ExternalV2ControllerTest {

    private RateShopService rateShopService;
    private PickupService pickupService;
    private ManifestService manifestService;
    private LandedCostService landedCostService;
    private ExternalV2Controller controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rateShopService = mock(RateShopService.class);
        pickupService = mock(PickupService.class);
        manifestService = mock(ManifestService.class);
        landedCostService = mock(LandedCostService.class);
        // Sprint 50 Tier 1 finding #7 — IdempotencyService requires a
        // StringRedisTemplate provider + ObjectMapper. All controller
        // methods in this test invoke the endpoint with idempotencyKey=null,
        // so executeOrReplay short-circuits to handler.get() without ever
        // touching Redis (that's the "not idempotent, pass through" branch).
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(null);
        // Sprint 50 PR K — IdempotencyService owns its own ObjectMapper now.
        IdempotencyService idempotency = new IdempotencyService(redisProvider);
        controller = new ExternalV2Controller(
                mock(ExternalApiService.class),
                rateShopService, pickupService, manifestService, landedCostService,
                idempotency);
    }

    @Test
    void rateShop_invokesServiceExactlyOnce() {
        when(rateShopService.rateShop(any())).thenReturn(okResponse(new RateShopResponseDTO()));
        controller.rateShop(new RateShopRequestDTO(), caller(), null);
        verify(rateShopService, times(1)).rateShop(any());
    }

    @Test
    void schedulePickup_invokesServiceExactlyOnce() {
        when(pickupService.schedule(any())).thenReturn(okResponse(new PickupResponseDTO()));
        controller.schedulePickup(new PickupRequestDTO(), caller(), null);
        verify(pickupService, times(1)).schedule(any());
    }

    @Test
    void closeOut_invokesServiceExactlyOnce() {
        when(manifestService.closeOut(any())).thenReturn(okResponse(new ManifestResponseDTO()));
        controller.closeOut(new ManifestRequestDTO(), caller(), null);
        verify(manifestService, times(1)).closeOut(any());
    }

    @Test
    void landedCost_invokesServiceExactlyOnce() {
        when(landedCostService.estimate(any())).thenReturn(okResponse(new LandedCostResponseDTO()));
        controller.landedCost(new LandedCostRequestDTO(), caller());
        verify(landedCostService, times(1)).estimate(any());
    }

    // ===== helpers =====

    private static ApiKeyPrincipal caller() {
        return new ApiKeyPrincipal(1L, "test-key", "ARHDEV", Set.of("*"));
    }

    private static <T> ApiResponse<T> okResponse(T data) {
        return ApiResponse.<T>builder()
                .status("SUCCESS").code(200).message("ok").data(data).build();
    }
}
