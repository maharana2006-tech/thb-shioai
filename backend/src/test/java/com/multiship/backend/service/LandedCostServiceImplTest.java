package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LandedCostRequestDTO;
import com.multiship.backend.dto.LandedCostResponseDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 50 Tier 0.5 PR H — the landed-cost estimate clamps its
 * caller-supplied {@code customerNo} BEFORE the carrier account is
 * resolved, so a scoped USER cannot probe another tenant's account.
 */
class LandedCostServiceImplTest {

    private CarrierService carrierService;
    private CarrierAccountRefRepository accountRepo;
    private TenantScopeEnforcer tenantScope;
    private LandedCostServiceImpl service;

    @BeforeEach
    void setUp() {
        carrierService = mock(CarrierService.class);
        accountRepo = mock(CarrierAccountRefRepository.class);
        tenantScope = mock(TenantScopeEnforcer.class);

        service = new LandedCostServiceImpl(carrierService, accountRepo);
        ReflectionTestUtils.setField(service, "tenantScope", tenantScope);
    }

    private LandedCostRequestDTO req(String customerNo) {
        return LandedCostRequestDTO.builder()
                .carrierCode("UPS")
                .customerNo(customerNo)
                .shipment(ShipmentRequestDTO.builder().build())
                .build();
    }

    @Test
    void estimate_scopedUserWithForeignCustomerNo_isRejectedAtClamp() {
        when(tenantScope.clampClientCode("OTHER"))
                .thenThrow(new AccessDeniedException("cross tenant"));

        assertThrows(AccessDeniedException.class, () -> service.estimate(req("OTHER")));
        // Clamp fires BEFORE the carrier lookup, so we should never see
        // the carrier connector requested.
        verify(carrierService, never()).getCarrierConnector(anyString());
        verify(accountRepo, never()).findByCustomerNoIgnoreCaseAndClientDefaultTrue(any());
    }

    @Test
    void estimate_scopedUserBlankCustomerNo_clampedToOwnTenant() {
        // Scoped USER as ACME — clamp on null returns their own tenant.
        when(tenantScope.clampClientCode(null)).thenReturn("ACME");
        // Force an early exit AFTER the clamp but before the connector
        // is invoked — a missing account (no platform config either) is
        // handled by returning a NOT_SUPPORTED success. We use the
        // fact that getCarrierConnector throws to short-circuit into
        // the "carrier isn't configured" failure branch and prove the
        // clamp happened first.
        when(carrierService.getCarrierConnector("UPS"))
                .thenThrow(new IllegalStateException("no connector"));

        ApiResponse<LandedCostResponseDTO> resp = service.estimate(req(null));

        assertEquals("error", resp.getStatus());
        verify(tenantScope).clampClientCode(null);
    }

    @Test
    void estimate_nullRequest_returnsBadRequestWithoutCallingClamp() {
        // Guard the pre-clamp validation branch is unchanged: null request
        // never reaches the tenant scope logic.
        ApiResponse<LandedCostResponseDTO> resp = service.estimate(null);
        assertEquals("error", resp.getStatus());
        verify(tenantScope, never()).clampClientCode(any());
    }
}
