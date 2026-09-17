package com.multiship.backend.service.carriers;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.service.CarrierServiceImpl;
import com.multiship.backend.service.TenantScopeEnforcer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR-S5 (STAMPS_COM audit hardening) — coverage of the manual-label
 * entry-point validation contract for Stamps.com new-order calls.
 *
 * <p>The full {@link CarrierServiceImpl#generateManualLabel(ManualShipmentRequest,
 * UserDetails, Integer)} orchestrator is ~500 lines and reaches ~30 collaborators
 * (tenantScope, carrierAccountRefRepository, shippingConfigService,
 * resolutionService, connector registry, orderRepository, trackingRepository,
 * PackagingValidator, RoutingRuleService, etc.). Standing up that harness in
 * pure Mockito is out of scope for the S-track (see the class Javadoc of
 * {@link com.multiship.backend.service.CarrierServiceImplTest} — the full
 * harness is deferred to a future sprint).
 *
 * <p>This suite pins the reachable-without-harness validation contract at the
 * top of {@code generateManualLabel} for Stamps ({@code carrierCode="STAMPS"})
 * new-order (existingOrderNo=null) requests:
 * <ul>
 *   <li>null request → 422 VALIDATION_ERROR "Recipient details are required."</li>
 *   <li>null recipient → 422 VALIDATION_ERROR (same message).</li>
 *   <li>recipient with blank name/address/city/postalCode/countryCode → 422
 *       VALIDATION_ERROR pinpointing the missing required fields.</li>
 *   <li>zero/negative weight → 422 VALIDATION_ERROR pinpointing the weight.</li>
 *   <li>invalid channel enum → 422 VALIDATION_ERROR pinpointing D2C/B2B.</li>
 *   <li>no accountId + no accountNumber → 422 VALIDATION_ERROR pinpointing the
 *       bill-to account.</li>
 * </ul>
 * These are the guards that STAMPS_COM connector calls must clear BEFORE any
 * SOAP wiring — a request that fails any of these must never reach
 * {@code StampsConnector.createShipment}. Follows the reflection-allocation
 * pattern from {@link com.multiship.backend.service.CarrierServiceUspsManualQueueTest}
 * — allocates the impl with null collaborators and pokes only the fields the
 * reachable validation branches need.
 *
 * <p>Happy-path connector-invocation coverage lives in the sibling connector
 * tests ({@link StampsConnectorPayloadTest}, {@link StampsSeraCreateLabelTest})
 * which pin the SOAP wiring directly rather than going through the outer
 * orchestrator.
 */
class StampsManualLabelGenerationTest {

    private CarrierServiceImpl service;
    private TenantScopeEnforcer tenantScope;

    @BeforeEach
    void setUp() throws Exception {
        service = allocate();
        tenantScope = mock(TenantScopeEnforcer.class);
        // Passthrough — the null-tenantScope caller would NPE past the recipient
        // guard, and every recipient-field / weight / channel / account test
        // needs to reach the next guard. Real tenant clamping is exercised in
        // TenantScopeEnforcerTest.
        when(tenantScope.clampClientCode(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(tenantScope.clampClientCode(null)).thenReturn(null);
        ReflectionTestUtils.setField(service, "tenantScope", tenantScope);
    }

    /**
     * Reflection allocation mirroring
     * {@link com.multiship.backend.service.CarrierServiceUspsManualQueueTest#allocate()}
     * — the pure-Mockito path can't call the all-args constructor without
     * wiring every collaborator, so we allocate with nulls and set only the
     * fields the guards under test need.
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

    /** Fully-valid recipient — every subsequent test blanks a single field
     *  to force the corresponding validation branch. */
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

    /** Minimal Stamps request that would proceed past the reachable-guard
     *  block (recipient valid, positive weight, bill-to supplied). Individual
     *  tests mutate one field to isolate a branch. */
    private static ManualShipmentRequest stampsRequest() {
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setCarrierCode("STAMPS");
        req.setAccountNumber("A12345");
        req.setWeight(new BigDecimal("1.5"));
        req.setWeightUnit("LB");
        req.setRecipient(validRecipient());
        return req;
    }

    // ================================================================
    // Null-request / null-recipient short-circuit — fires BEFORE
    // tenantScope wiring, so this branch would fire even for null scope.
    // ================================================================

    @Test
    void nullRequestReturnsRecipientRequired422() {
        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(null, operator(), null);

        assertNotNull(resp);
        assertEquals("error", resp.getStatus());
        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
        assertTrue(resp.getMessage().toLowerCase().contains("recipient"),
                "operator-facing message must name the missing recipient: " + resp.getMessage());
    }

    @Test
    void nullRecipientReturnsRecipientRequired422() {
        ManualShipmentRequest req = stampsRequest();
        req.setRecipient(null);

        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(req, operator(), null);

        assertEquals("error", resp.getStatus());
        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
    }

    // ================================================================
    // Recipient-field validation branches — reached after the null guards
    // and the tenantScope clamp (mocked passthrough).
    // ================================================================

    @Test
    void blankRecipientNameReturns422() {
        ManualShipmentRequest req = stampsRequest();
        req.getRecipient().setName(" ");

        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(req, operator(), null);

        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
        assertTrue(resp.getMessage().toLowerCase().contains("name"),
                "message must name the missing field: " + resp.getMessage());
    }

    @Test
    void blankAddressLine1Returns422() {
        ManualShipmentRequest req = stampsRequest();
        req.getRecipient().setAddressLine1(null);

        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(req, operator(), null);

        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
    }

    @Test
    void blankRecipientCityReturns422() {
        ManualShipmentRequest req = stampsRequest();
        req.getRecipient().setCity("");

        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(req, operator(), null);

        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
    }

    @Test
    void blankPostalCodeReturns422() {
        ManualShipmentRequest req = stampsRequest();
        req.getRecipient().setPostalCode(null);

        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(req, operator(), null);

        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
    }

    @Test
    void blankCountryCodeReturns422() {
        ManualShipmentRequest req = stampsRequest();
        req.getRecipient().setCountryCode("");

        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(req, operator(), null);

        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
    }

    // ================================================================
    // Weight validation — reached after recipient fields validate clean.
    // ================================================================

    @Test
    void zeroWeightReturns422() {
        ManualShipmentRequest req = stampsRequest();
        req.setWeight(BigDecimal.ZERO);

        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(req, operator(), null);

        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
        assertTrue(resp.getMessage().toLowerCase().contains("weight"),
                "message must name the weight requirement: " + resp.getMessage());
    }

    @Test
    void negativeWeightReturns422() {
        ManualShipmentRequest req = stampsRequest();
        req.setWeight(new BigDecimal("-0.1"));

        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(req, operator(), null);

        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
    }

    @Test
    void nullWeightReturns422() {
        ManualShipmentRequest req = stampsRequest();
        req.setWeight(null);

        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(req, operator(), null);

        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
    }

    // ================================================================
    // Channel enum gate — D2C / B2B only.
    // ================================================================

    @Test
    void invalidChannelReturns422() {
        ManualShipmentRequest req = stampsRequest();
        req.setChannel("DTC"); // typo — must be D2C

        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(req, operator(), null);

        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
        assertTrue(resp.getMessage().contains("D2C") || resp.getMessage().contains("B2B"),
                "message must name the accepted channel values: " + resp.getMessage());
    }

    // ================================================================
    // Bill-to account required — neither accountId nor typed number.
    // ================================================================

    @Test
    void missingBillToReturns422() {
        ManualShipmentRequest req = stampsRequest();
        req.setAccountNumber(null);
        req.setAccountId(null);

        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(req, operator(), null);

        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
        assertTrue(resp.getMessage().toLowerCase().contains("account"),
                "message must name the missing bill-to: " + resp.getMessage());
    }

    // ================================================================
    // 2-arg overload delegates to the 3-arg with null existingOrderNo.
    // ================================================================

    @Test
    void twoArgOverloadDelegatesToNewOrderPath() {
        // The 2-arg overload is what /shipments/manual POSTs to; verifies
        // the shim passes null for existingOrderNo (new-order path).
        ApiResponse<LabelGenerationResponse> resp =
                service.generateManualLabel(null, operator());

        assertNotNull(resp);
        assertEquals(422, resp.getCode(),
                "2-arg null-request must still hit the null-request guard in the 3-arg body");
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
    }
}
