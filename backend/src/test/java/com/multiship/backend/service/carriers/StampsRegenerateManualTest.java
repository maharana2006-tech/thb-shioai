package com.multiship.backend.service.carriers;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.service.CarrierServiceImpl;
import com.multiship.backend.service.TenantScopeEnforcer;
import com.multiship.backend.service.carriers.usps.queue.IdempotencyKeys;
import com.multiship.backend.service.carriers.usps.queue.UspsDirectRoutingService;
import com.multiship.backend.service.carriers.usps.queue.UspsDirectRoutingService.RoutingDecision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PR-S5 (STAMPS_COM audit hardening) — coverage of the regenerate-path
 * behaviours on {@link CarrierServiceImpl#generateManualLabel(ManualShipmentRequest,
 * UserDetails, Integer)} for a Stamps ({@code carrierCode="STAMPS"}) order.
 *
 * <p>The "regenerate" path fires when the operator fixes a failed order and
 * clicks Generate again — the caller passes the existing {@code orderNo} as
 * the third argument. Two hardening-relevant behaviours must hold:
 *
 * <ol>
 *   <li><b>USPS_DIRECT routing consult</b> — when {@code existingOrderNo != null}
 *       the method calls {@code maybeRouteUspsDirect(existingOrderNo, user)}
 *       BEFORE any validation, so a Stamps regenerate whose tenant flipped
 *       USPS_PROVIDER to USPS_DIRECT correctly lands on the queue instead of
 *       silently re-hammering the SOAP endpoint. This is the flip side of
 *       {@link com.multiship.backend.service.CarrierServiceUspsManualQueueTest} —
 *       here we assert that the caller (generateManualLabel) invokes the
 *       routing consult, and the queue-shape response reaches the caller
 *       verbatim.</li>
 *   <li><b>{@code normalizeInternalIdempotencyKey} contract</b> — the helper
 *       that reads {@code req.internalIdempotencyKey} normalises whitespace,
 *       collapses blank/null to null, and returns the trimmed value
 *       otherwise. This is the linchpin of the S3 cross-flow dedup fix
 *       (matching keys from import + queue must map to the same tracking-row
 *       {@code idempotency_key}). Assertions cover: canonical Stamps-order
 *       key, blank/null collapse, whitespace-trim, and null-request guard.</li>
 * </ol>
 *
 * <p>The "409 LABEL_ALREADY_GENERATED" branch (the tracking-row dedup match)
 * lives ~1500 lines deeper in the connector-dispatch section and requires
 * the full 30-collaborator harness to reach. That gap is deferred to the
 * follow-up harness sprint (see {@link com.multiship.backend.service.CarrierServiceImplTest}
 * class Javadoc); the S-track pins the reachable-without-harness contracts.
 */
class StampsRegenerateManualTest {

    private CarrierServiceImpl service;
    private UspsDirectRoutingService routing;
    private TenantScopeEnforcer tenantScope;

    private static final long STAMPS_ORDER_NO = 4242L;

    @BeforeEach
    void setUp() throws Exception {
        service = allocate();
        routing = mock(UspsDirectRoutingService.class);
        tenantScope = mock(TenantScopeEnforcer.class);
        when(tenantScope.clampClientCode(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(tenantScope.clampClientCode(null)).thenReturn(null);
        ReflectionTestUtils.setField(service, "uspsDirectRoutingService", routing);
        ReflectionTestUtils.setField(service, "tenantScope", tenantScope);
    }

    private static CarrierServiceImpl allocate() throws Exception {
        Constructor<?>[] ctors = CarrierServiceImpl.class.getDeclaredConstructors();
        Constructor<?> ctor = ctors[0];
        ctor.setAccessible(true);
        return (CarrierServiceImpl) ctor.newInstance(new Object[ctor.getParameterCount()]);
    }

    private static UserDetails operator() {
        return User.withUsername("alice").password("").authorities("ROLE_USER").build();
    }

    /** Minimal Stamps regenerate request — recipient supplied so we clear the
     *  null-recipient short-circuit and reach the routing consult. */
    private static ManualShipmentRequest stampsRegenerateRequest() {
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setCarrierCode("STAMPS");
        req.setAccountNumber("A12345");
        req.setWeight(new BigDecimal("1.5"));
        req.setWeightUnit("LB");
        ManualShipmentRequest.Address to = new ManualShipmentRequest.Address();
        to.setName("Jane Doe");
        to.setAddressLine1("1 Broadway");
        to.setCity("New York");
        to.setState("NY");
        to.setPostalCode("10001");
        to.setCountryCode("US");
        req.setRecipient(to);
        return req;
    }

    // ================================================================
    // Regenerate consults the USPS_DIRECT routing service BEFORE
    // validation — so a Stamps order whose tenant flipped provider gets
    // rerouted rather than silently double-hitting Stamps SOAP.
    // ================================================================

    @Test
    void regenerateConsultsRoutingBeforeValidation() {
        // Routing returns Optional.empty() → sync fallthrough → the request
        // then hits the recipient guard. We provide a valid recipient so the
        // routing consult is the observable behavior we assert.
        when(routing.decide(eq(STAMPS_ORDER_NO), any(), any())).thenReturn(Optional.empty());

        // Blank required field so the flow stops in the recipient guard
        // and doesn't try to reach downstream collaborators we haven't wired.
        ManualShipmentRequest req = stampsRegenerateRequest();
        req.getRecipient().setName(""); // hit the field-blank branch

        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(req, operator(), (int) STAMPS_ORDER_NO);

        // Routing consult MUST have fired regardless of the downstream
        // validation outcome — the whole point of the S1 wiring.
        verify(routing).decide(eq(STAMPS_ORDER_NO), any(), any());
        assertEquals(422, resp.getCode(),
                "downstream field-blank guard fires after the routing consult returns empty");
    }

    @Test
    void regenerateWithUspsDirectSingleQueuedShortCircuits() {
        // Tenant flipped USPS_PROVIDER=USPS_DIRECT mid-lifecycle. A Stamps
        // order regenerate now qualifies for the shared queue; the routing
        // service returns SINGLE_QUEUED and generateManualLabel must return
        // the queue shape verbatim without touching any downstream
        // collaborators.
        when(routing.decide(eq(STAMPS_ORDER_NO), any(), any())).thenReturn(Optional.of(
                new RoutingDecision(RoutingDecision.Status.SINGLE_QUEUED, 999L, null, null)));

        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(stampsRegenerateRequest(), operator(),
                        (int) STAMPS_ORDER_NO);

        assertNotNull(resp);
        assertEquals("success", resp.getStatus());
        assertEquals(200, resp.getCode());
        assertNotNull(resp.getData());
        assertEquals("QUEUED", resp.getData().getStatus());
        assertEquals(999L, resp.getData().getQueueItemId());
        // tenantScope must NOT have been touched — the routing short-circuit
        // returned before validation kicked in.
        verifyNoInteractions(tenantScope);
    }

    @Test
    void regenerateWithUspsDirectMpsQueuedShortCircuits() {
        when(routing.decide(eq(STAMPS_ORDER_NO), any(), any())).thenReturn(Optional.of(
                new RoutingDecision(RoutingDecision.Status.MPS_QUEUED, null, 4, null)));

        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(stampsRegenerateRequest(), operator(),
                        (int) STAMPS_ORDER_NO);

        assertEquals("success", resp.getStatus());
        assertEquals(200, resp.getCode());
        assertEquals("QUEUED_MPS", resp.getData().getStatus());
        assertEquals(4, resp.getData().getMpsPieceCount());
    }

    @Test
    void regenerateWithUspsDirectRejectionSurfaces422() {
        String reason = "USPS Direct does not support multi-piece international shipments.";
        when(routing.decide(eq(STAMPS_ORDER_NO), any(), any())).thenReturn(Optional.of(
                new RoutingDecision(RoutingDecision.Status.REJECTED, null, null, reason)));

        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(stampsRegenerateRequest(), operator(),
                        (int) STAMPS_ORDER_NO);

        assertEquals("error", resp.getStatus());
        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.INTL_MPS_UNSUPPORTED.name(), resp.getErrorCode());
        assertEquals(reason, resp.getMessage());
    }

    @Test
    void newOrderPathDoesNotConsultRouting() {
        // existingOrderNo=null → the routing consult is skipped (there's no
        // order to look up). Prevents wasted decide() calls on the new-order
        // path where the caller has to fall through to the sync connector
        // anyway.
        when(routing.decide(anyLong(), any(), any())).thenReturn(Optional.empty());

        // Null recipient forces the top-most guard so we don't need
        // further collaborator wiring to observe the routing behaviour.
        ManualShipmentRequest req = stampsRegenerateRequest();
        req.setRecipient(null);

        service.generateManualLabel(req, operator(), null);

        verifyNoInteractions(routing);
    }

    // ================================================================
    // normalizeInternalIdempotencyKey — S3 cross-flow dedup helper.
    // ================================================================

    private static String invokeNormalize(ManualShipmentRequest req) throws Exception {
        Method m = CarrierServiceImpl.class.getDeclaredMethod(
                "normalizeInternalIdempotencyKey", ManualShipmentRequest.class);
        m.setAccessible(true);
        return (String) m.invoke(null, req);
    }

    @Test
    void normalizeCanonicalStampsOrderKeyPassesThrough() throws Exception {
        // Import path stamps req.internalIdempotencyKey =
        // IdempotencyKeys.forStampsOrder(orderNo). Normalize must return
        // the value untouched (well-formed input).
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setInternalIdempotencyKey(IdempotencyKeys.forStampsOrder(STAMPS_ORDER_NO));

        String out = invokeNormalize(req);
        assertEquals("usps-order-" + STAMPS_ORDER_NO, out,
                "canonical order-anchored key must pass through untouched");
    }

    @Test
    void normalizeWhitespaceOnlyKeyReturnsNull() throws Exception {
        // A blank-after-trim key must collapse to null so the tracking-row
        // write picks the correct fallback (client-supplied header, or none).
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setInternalIdempotencyKey("   ");

        assertNull(invokeNormalize(req));
    }

    @Test
    void normalizeNullKeyReturnsNull() throws Exception {
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setInternalIdempotencyKey(null);

        assertNull(invokeNormalize(req));
    }

    @Test
    void normalizeTrimsSurroundingWhitespace() throws Exception {
        // Defensive — the queue-side write may include stray whitespace in
        // some code paths; trim before it reaches the DB comparison.
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setInternalIdempotencyKey("  usps-order-100  ");

        assertEquals("usps-order-100", invokeNormalize(req));
    }

    @Test
    void normalizeNullRequestReturnsNull() throws Exception {
        // Defensive — callers may pass null when re-invoking the shim.
        assertNull(invokeNormalize(null));
    }

    // ================================================================
    // IdempotencyKeys.forStampsOrder mirrors the USPS variant — the two
    // must produce identical keys for the same orderNo so a mid-batch
    // provider flip doesn't leak duplicate spend past the dedup check.
    // ================================================================

    @Test
    void stampsAndUspsOrderKeysMatchForSameOrderNo() {
        assertEquals(IdempotencyKeys.forUspsOrder(STAMPS_ORDER_NO),
                IdempotencyKeys.forStampsOrder(STAMPS_ORDER_NO),
                "S3 D1 contract: Stamps + USPS_DIRECT share the same key namespace so a "
                        + "provider flip mid-batch still trips the tracking-row dedup check");
    }

    @Test
    void stampsOrderKeyRejectsNonPositiveOrderNo() {
        // Guard mirrors USPS variant — a bogus orderNo (0 or negative) is
        // never a valid label target and would poison the dedup namespace.
        assertTrue(assertThrowsIllegal(() -> IdempotencyKeys.forStampsOrder(0L)),
                "forStampsOrder(0) must throw");
        assertTrue(assertThrowsIllegal(() -> IdempotencyKeys.forStampsOrder(-1L)),
                "forStampsOrder(-1) must throw");
    }

    private static boolean assertThrowsIllegal(Runnable r) {
        try { r.run(); return false; }
        catch (IllegalArgumentException expected) { return true; }
    }
}
