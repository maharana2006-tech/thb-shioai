package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.dto.ManualShipmentRequest;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BUG-005 — the typed {@code carrierCode} on a manual-label request must
 * match the picked account's carrier. Pre-fix a FEDEX code with a UPS
 * account id called FedEx with UPS credentials (502 NOT.AUTHORIZED) and
 * persisted an ERROR order. Now the mismatch short-circuits at the
 * manual-label entry point before any carrier call or order write.
 *
 * <p>Follows the reflection-allocation pattern established by
 * {@link com.multiship.backend.service.carriers.StampsManualLabelGenerationTest}
 * — allocate the impl with null collaborators and set only what the
 * mismatch branch reads.
 */
class CarrierServiceCarrierMismatchTest {

    private CarrierServiceImpl service;
    private TenantScopeEnforcer tenantScope;
    private CarrierAccountRefRepository carrierAccountRefRepository;

    @BeforeEach
    void setUp() throws Exception {
        service = allocate();
        tenantScope = mock(TenantScopeEnforcer.class);
        carrierAccountRefRepository = mock(CarrierAccountRefRepository.class);
        when(tenantScope.clampClientCode(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(tenantScope.clampClientCode(null)).thenReturn(null);
        ReflectionTestUtils.setField(service, "tenantScope", tenantScope);
        ReflectionTestUtils.setField(service, "carrierAccountRefRepository", carrierAccountRefRepository);
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

    /** Minimal request that will reach account resolution — client-less so
     *  the ship-to allowlist / warehouse branches are no-ops. */
    private static ManualShipmentRequest baseRequest(String carrierCode, Long accountId) {
        ManualShipmentRequest req = new ManualShipmentRequest();
        req.setCarrierCode(carrierCode);
        req.setAccountId(accountId);
        req.setWeight(new BigDecimal("1.5"));
        req.setWeightUnit("LB");
        req.setRecipient(validRecipient());
        return req;
    }

    private static CarrierAccountRef upsAccount(Long id) {
        CarrierAccountRef acct = new CarrierAccountRef();
        acct.setId(id);
        acct.setCarrierCode("UPS");
        acct.setAccountNumber("UPS-123");
        acct.setActive(Boolean.TRUE);
        // customerNo blank so client-vs-owner guard is a no-op
        return acct;
    }

    // ─── BUG-005 ────────────────────────────────────────────────────────

    @Test
    void carrierCodeFedexWithUpsAccountReturns422() {
        when(carrierAccountRefRepository.findById(2L)).thenReturn(Optional.of(upsAccount(2L)));

        ApiResponse<LabelGenerationResponse> resp =
                service.purchaseManualLabel(baseRequest("FEDEX", 2L), operator(), null);

        assertEquals("error", resp.getStatus());
        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
        assertTrue(resp.getMessage().contains("does not match"),
                "operator-facing message must name the mismatch: " + resp.getMessage());
        assertTrue(resp.getMessage().contains("FEDEX") && resp.getMessage().contains("UPS"),
                "message must name both sides: " + resp.getMessage());
    }

    @Test
    void carrierCodeFooWithUpsAccountReturns422() {
        // Unknown carrier string ("FOO") against a UPS account — same guard
        // fires. Previously this slipped past because resolveCanonicalCarrierCode
        // returns the raw code when canonicalCarrierFor is blank.
        when(carrierAccountRefRepository.findById(2L)).thenReturn(Optional.of(upsAccount(2L)));

        ApiResponse<LabelGenerationResponse> resp =
                service.purchaseManualLabel(baseRequest("FOO", 2L), operator(), null);

        assertEquals(422, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
        assertTrue(resp.getMessage().contains("does not match"),
                "message must name the mismatch: " + resp.getMessage());
    }
}
