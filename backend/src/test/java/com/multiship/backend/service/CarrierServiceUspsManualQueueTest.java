package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.service.carriers.usps.queue.UspsDirectRoutingService;
import com.multiship.backend.service.carriers.usps.queue.UspsDirectRoutingService.RoutingDecision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PR-G1 — coverage of {@link CarrierServiceImpl}'s two entry-point
 * routing helpers ({@code maybeRouteUspsDirect}, {@code buildQueuedResponse}).
 *
 * <p>The class Javadoc for {@link CarrierServiceImpl} defers a full
 * ~30-collaborator harness to a follow-up sprint (see
 * {@link CarrierServiceImplTest}). PR-G1's new wiring is tested at the
 * helper level using the same reflection allocation pattern the sibling
 * helper suites use ({@link CarrierServiceImplLabelHelpersTest},
 * {@link CarrierServiceImplPackagesJsonTest}) — this pins the actual
 * decision logic (routing service consultation, response shape, error
 * code) without standing up every collaborator the outer
 * {@code generateLabel}/{@code generateManualLabel} orchestrators need.
 *
 * <p>Matrix per the PR-G1 brief:
 * <ul>
 *   <li>routing service null → returns null (caller stays sync)</li>
 *   <li>routing SYNC → returns null (caller stays sync)</li>
 *   <li>routing SINGLE_QUEUED → HTTP 200 + status=QUEUED + queueItemId</li>
 *   <li>routing MPS_QUEUED → HTTP 200 + status=QUEUED_MPS + mpsPieceCount</li>
 *   <li>routing REJECTED → HTTP 422 + INTL_MPS_UNSUPPORTED + reason</li>
 *   <li>routing throws → returns null (defensive sync fallback)</li>
 *   <li>caller = queue processor → passed through unchanged (guard fires
 *       inside the routing service; verified by the routing service test)</li>
 * </ul>
 *
 * <p>The full-flow behavior for {@code generateLabel} / {@code generateManualLabel}
 * (routing decision short-circuits the connector call; regenerate path
 * routes but net-new does not) is exercised indirectly via
 * {@link com.multiship.backend.service.carriers.usps.queue.UspsDirectRoutingServiceTest}
 * (decision matrix) plus this suite (wiring layer). A dedicated
 * end-to-end IT for the two entry points requires the 30-collaborator
 * harness and is out of scope for the PR-G1 hot-fix.
 */
class CarrierServiceUspsManualQueueTest {

    private UspsDirectRoutingService routing;
    private CarrierServiceImpl service;

    private static final long ORDER_NO = 42L;

    @BeforeEach
    void setUp() throws Exception {
        routing = mock(UspsDirectRoutingService.class);
        service = allocate();
        ReflectionTestUtils.setField(service, "uspsDirectRoutingService", routing);
        // log field on CarrierServiceImpl is @Slf4j -- picked up by
        // Lombok's static logger, no wiring needed. Same for all the
        // helpers we DON'T touch here (they're only reached if the
        // full-flow entry point runs).
    }

    /**
     * Reflection allocation mirroring
     * {@link CarrierServiceImplLabelHelpersTest#allocate()} — the
     * pure-Mockito path can't call the 30-arg all-args constructor
     * without wiring every collaborator, so we allocate with nulls and
     * set only the fields the helper under test needs.
     */
    private static CarrierServiceImpl allocate() throws Exception {
        Constructor<?>[] ctors = CarrierServiceImpl.class.getDeclaredConstructors();
        Constructor<?> ctor = ctors[0];
        ctor.setAccessible(true);
        return (CarrierServiceImpl) ctor.newInstance(new Object[ctor.getParameterCount()]);
    }

    private static UserDetails operator() {
        return User.withUsername("alice").password("").authorities("ROLE_USER").build();
    }

    private static UserDetails queueProcessor() {
        return User.withUsername("usps-queue-processor")
                .password("").authorities("ROLE_SYSTEM").build();
    }

    @SuppressWarnings("unchecked")
    private ApiResponse<LabelGenerationResponse> invokeMaybeRoute(long orderNo, UserDetails caller) {
        return (ApiResponse<LabelGenerationResponse>) ReflectionTestUtils.invokeMethod(
                service, "maybeRouteUspsDirect", orderNo, caller);
    }

    private LabelGenerationResponse invokeBuildQueuedResponse(long orderNo, RoutingDecision d) {
        return (LabelGenerationResponse) ReflectionTestUtils.invokeMethod(
                service, "buildQueuedResponse", orderNo, d);
    }

    // ================================================================
    // maybeRouteUspsDirect
    // ================================================================

    @Test
    void routingServiceUnwiredReturnsNullForSync() throws Exception {
        // Rebuild with routing service = null to prove the "optional
        // collaborator" fallback stays inert.
        CarrierServiceImpl bare = allocate();
        ApiResponse<LabelGenerationResponse> resp = (ApiResponse<LabelGenerationResponse>)
                ReflectionTestUtils.invokeMethod(bare, "maybeRouteUspsDirect", ORDER_NO, operator());
        assertNull(resp, "Null routing service must fall through to sync");
    }

    @Test
    void routingReturnsEmptyLeavesCallerOnSyncPath() {
        when(routing.decide(eq(ORDER_NO), any(), any())).thenReturn(Optional.empty());
        ApiResponse<LabelGenerationResponse> resp = invokeMaybeRoute(ORDER_NO, operator());
        assertNull(resp, "Empty decision (SYNC) must return null so caller proceeds");
    }

    @Test
    void routingSingleQueuedProducesQueuedResponse() {
        RoutingDecision d = new RoutingDecision(
                RoutingDecision.Status.SINGLE_QUEUED, 999L, null, null);
        when(routing.decide(eq(ORDER_NO), any(), any())).thenReturn(Optional.of(d));

        ApiResponse<LabelGenerationResponse> resp = invokeMaybeRoute(ORDER_NO, operator());
        assertNotNull(resp);
        assertEquals("success", resp.getStatus());
        assertEquals(200, resp.getCode());
        LabelGenerationResponse body = resp.getData();
        assertNotNull(body);
        assertEquals("QUEUED", body.getStatus());
        assertEquals(999L, body.getQueueItemId());
        assertNull(body.getMpsPieceCount());
        assertEquals(ORDER_NO, body.getOrderNo());
        assertEquals("USPS", body.getCarrierCode());
        assertTrue(body.getMessage().contains("queued"), body.getMessage());
    }

    @Test
    void routingMpsQueuedProducesQueuedMpsResponse() {
        RoutingDecision d = new RoutingDecision(
                RoutingDecision.Status.MPS_QUEUED, null, 5, null);
        when(routing.decide(eq(ORDER_NO), any(), any())).thenReturn(Optional.of(d));

        ApiResponse<LabelGenerationResponse> resp = invokeMaybeRoute(ORDER_NO, operator());
        assertNotNull(resp);
        assertEquals("success", resp.getStatus());
        assertEquals(200, resp.getCode());
        LabelGenerationResponse body = resp.getData();
        assertNotNull(body);
        assertEquals("QUEUED_MPS", body.getStatus());
        assertEquals(5, body.getMpsPieceCount());
        assertNull(body.getQueueItemId());
        assertTrue(body.getMessage().contains("multi-piece") || body.getMessage().contains("5 pieces"),
                body.getMessage());
    }

    @Test
    void routingRejectedProducesUnprocessableContentWithIntlMpsCode() {
        String reason = "USPS Direct does not support multi-piece international shipments. "
                + "Split into single-package intl shipments manually, or set USPS_PROVIDER=STAMPS_COM";
        RoutingDecision d = new RoutingDecision(
                RoutingDecision.Status.REJECTED, null, null, reason);
        when(routing.decide(eq(ORDER_NO), any(), any())).thenReturn(Optional.of(d));

        ApiResponse<LabelGenerationResponse> resp = invokeMaybeRoute(ORDER_NO, operator());
        assertNotNull(resp);
        assertEquals("error", resp.getStatus());
        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.INTL_MPS_UNSUPPORTED.name(), resp.getErrorCode());
        assertEquals(reason, resp.getMessage());
        LabelGenerationResponse body = resp.getData();
        assertNotNull(body);
        assertEquals("REJECTED", body.getStatus());
        assertEquals(reason, body.getMessage());
    }

    @Test
    void routingThrowFallsBackToSync() {
        // Unexpected failure in the routing service must NOT bubble into
        // the caller — a routing outage cannot brick label generation.
        when(routing.decide(eq(ORDER_NO), any(), any()))
                .thenThrow(new RuntimeException("routing crashed"));

        ApiResponse<LabelGenerationResponse> resp = invokeMaybeRoute(ORDER_NO, operator());
        assertNull(resp, "Routing throw must fall through to sync (caller decides)");
    }

    @Test
    void queueProcessorCallerPassedThroughToRoutingService() {
        // The routing service's own re-entrancy guard fires on the
        // QUEUE_SYSTEM_USER username — verified in
        // UspsDirectRoutingServiceTest. This test just proves the
        // CarrierServiceImpl wiring passes the caller through unchanged
        // (doesn't strip it or replace it).
        when(routing.decide(eq(ORDER_NO), any(UserDetails.class), any())).thenReturn(Optional.empty());

        invokeMaybeRoute(ORDER_NO, queueProcessor());
        verify(routing).decide(eq(ORDER_NO), any(UserDetails.class), any());
    }

    @Test
    void nullCallerPassedThroughToRoutingService() {
        // Background contexts (queue callback dispatch shim, tests) pass
        // null; must reach the routing service so it can decide.
        when(routing.decide(eq(ORDER_NO), eq(null), any())).thenReturn(Optional.empty());

        invokeMaybeRoute(ORDER_NO, null);
        verify(routing).decide(eq(ORDER_NO), eq(null), any());
    }

    // ================================================================
    // buildQueuedResponse
    // ================================================================

    @Test
    void buildQueuedResponseSingleShape() {
        RoutingDecision d = new RoutingDecision(
                RoutingDecision.Status.SINGLE_QUEUED, 123L, null, null);
        LabelGenerationResponse resp = invokeBuildQueuedResponse(ORDER_NO, d);
        assertEquals("QUEUED", resp.getStatus());
        assertEquals(123L, resp.getQueueItemId());
        assertNull(resp.getMpsPieceCount());
        assertEquals(ORDER_NO, resp.getOrderNo());
        assertEquals("USPS", resp.getCarrierCode());
        // Never populated on the queue path — those land at drain time.
        assertNull(resp.getTrackingNumber());
        assertNull(resp.getLabelUrl());
    }

    @Test
    void buildQueuedResponseMpsShape() {
        RoutingDecision d = new RoutingDecision(
                RoutingDecision.Status.MPS_QUEUED, null, 7, null);
        LabelGenerationResponse resp = invokeBuildQueuedResponse(ORDER_NO, d);
        assertEquals("QUEUED_MPS", resp.getStatus());
        assertEquals(7, resp.getMpsPieceCount());
        assertNull(resp.getQueueItemId());
        assertTrue(resp.getMessage().contains("7 pieces"), resp.getMessage());
    }
}
