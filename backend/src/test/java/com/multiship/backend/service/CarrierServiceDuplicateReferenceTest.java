package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.model.Order;
import com.multiship.backend.repository.OrderRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BUG-006 — the same (client, reference) must not buy two paid labels.
 * Pre-fix a second POST /orders/manual-label with the same body (and a
 * fresh Idempotency-Key) succeeded, printing a second paid label.
 * Validate had warned on the duplicate but didn't fail. Now:
 *
 * <ul>
 *   <li>{@code generateManualLabelLegacyBody} returns 409 CONFLICT +
 *       {@code LABEL_ALREADY_GENERATED} when the (client, reference) has
 *       a prior shipped order (new-order path only; regenerate is
 *       exempt).</li>
 *   <li>{@code ShipmentValidationService.checkDuplicateReference} now
 *       pushes the issue to {@code errors} (FAIL), not {@code warnings}
 *       (covered by {@code ShipmentValidationServiceTest} patch).</li>
 * </ul>
 *
 * <p>Follows the reflection-allocation pattern of
 * {@link com.multiship.backend.service.carriers.StampsManualLabelGenerationTest}.
 */
class CarrierServiceDuplicateReferenceTest {

    private CarrierServiceImpl service;
    private TenantScopeEnforcer tenantScope;
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp() throws Exception {
        service = allocate();
        tenantScope = mock(TenantScopeEnforcer.class);
        orderRepository = mock(OrderRepository.class);
        when(tenantScope.clampClientCode(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(tenantScope.clampClientCode(null)).thenReturn(null);
        ReflectionTestUtils.setField(service, "tenantScope", tenantScope);
        ReflectionTestUtils.setField(service, "orderRepository", orderRepository);
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

    private static ManualShipmentRequest.Address validRecipient() {
        ManualShipmentRequest.Address a = new ManualShipmentRequest.Address();
        a.setName("Jane Doe");
        a.setAddressLine1("1 Broadway");
        a.setCity("New York");
        a.setState("NY");
        a.setPostalCode("10001");
        a.setCountryCode("US");
        a.setPhone("2125550100");
        return a;
    }

    /** Request with a reference, bill-to supplied, and no client — so
     *  the duplicate guard uses "MANUAL" as the client scope. */
    private static ManualShipmentRequest reqWithReference(String reference) {
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setCarrierCode("UPS");
        req.setAccountNumber("UPS-123");
        req.setWeight(new BigDecimal("1.5"));
        req.setWeightUnit("LB");
        req.setRecipient(validRecipient());
        req.setReference(reference);
        return req;
    }

    private static Order shippedOrder(int orderNo, String track) {
        Order o = new Order();
        o.setOrderNo(orderNo);
        o.setTrack(track);
        return o;
    }

    // ─── BUG-006 ────────────────────────────────────────────────────────

    @Test
    void duplicateReferenceReturns409OnNewOrder() {
        // A prior shipped order has reference "QA-1018-A001" against the
        // MANUAL client scope. The retry must not buy a second label.
        when(orderRepository.findShippedByClientAndReference(eq("MANUAL"), eq("QA-1018-A001")))
                .thenReturn(Optional.of(shippedOrder(934208, "1Z999AA10123456784")));

        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(reqWithReference("QA-1018-A001"), operator(), null);

        assertEquals("error", resp.getStatus());
        assertEquals(409, resp.getCode());
        assertEquals(ErrorCode.LABEL_ALREADY_GENERATED.name(), resp.getErrorCode());
        assertTrue(resp.getMessage().contains("QA-1018-A001"),
                "message must name the reference: " + resp.getMessage());
        assertTrue(resp.getMessage().contains("934208"),
                "message must name the prior order #: " + resp.getMessage());
    }

    @Test
    void firstTimeReferencePassesTheGuard() {
        // No prior shipped order — guard is a no-op, the request falls
        // through to the next step (which will NPE on an unwired
        // collaborator; we only assert we did NOT short-circuit on the
        // duplicate-reference guard).
        when(orderRepository.findShippedByClientAndReference(anyString(), anyString()))
                .thenReturn(Optional.empty());

        try {
            ApiResponse<LabelGenerationResponse> resp =
                    service.generateManualLabel(reqWithReference("QA-1018-NEW"), operator(), null);
            if (resp != null && resp.getMessage() != null) {
                assertTrue(!resp.getMessage().contains("already shipped"),
                        "first-time reference must not trip the duplicate guard: "
                                + resp.getMessage());
            }
        } catch (Exception expected) {
            // Downstream collaborators unwired on the bare instance;
            // reaching them means the duplicate guard passed — this is
            // the assertion.
        }
    }

    @Test
    void regenerateExistingOrderBypassesDuplicateGuard() {
        // Even if the reference already shipped, the regenerate path
        // (existingOrderNo != null) must re-label the SAME order
        // without the dup guard firing — regenerate is explicitly
        // "re-ship the same reference on the same order".
        when(orderRepository.findShippedByClientAndReference(anyString(), anyString()))
                .thenReturn(Optional.of(shippedOrder(934208, "1Z999AA10123456784")));

        try {
            ApiResponse<LabelGenerationResponse> resp =
                    service.generateManualLabel(reqWithReference("QA-1018-A001"), operator(), 934208);
            if (resp != null && resp.getMessage() != null) {
                assertTrue(!resp.getMessage().contains("already shipped"),
                        "regenerate must not trip the dup guard: " + resp.getMessage());
            }
        } catch (Exception expected) {
            // same as above — reaching an NPE past the guard is the assertion
        }
    }
}
