package com.multiship.backend.service;

import com.multiship.backend.dto.AccountRefUpsertRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierAccountRefDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 50 Tier 0.5 PR H — tenant-scope guard tests for
 * {@link AccountRefServiceImpl}. Each write path either clamps the
 * incoming customerNo (upsert) or requires a tenant match on the
 * persisted row (verify/setClientDefault/toggleActive/deleteAccount).
 *
 * <p>Pattern: mock {@link TenantScopeEnforcer} and inject it via
 * {@link ReflectionTestUtils}, then assert the reject-on-foreign path
 * throws {@link AccessDeniedException} and the operator (pass-through)
 * path returns a success ApiResponse.
 */
class AccountRefServiceImplTest {

    private CarrierAccountRefRepository accountRepo;
    private OrderTrackingRepository trackingRepo;
    private CarrierService carrierService;
    private AuditService auditService;
    private ApplicationEventPublisher publisher;
    private TenantScopeEnforcer tenantScope;
    private AccountRefServiceImpl service;

    @BeforeEach
    void setUp() {
        accountRepo = mock(CarrierAccountRefRepository.class);
        trackingRepo = mock(OrderTrackingRepository.class);
        carrierService = mock(CarrierService.class);
        auditService = mock(AuditService.class);
        publisher = mock(ApplicationEventPublisher.class);
        tenantScope = mock(TenantScopeEnforcer.class);

        service = new AccountRefServiceImpl(
                accountRepo, trackingRepo, carrierService, auditService, publisher);
        ReflectionTestUtils.setField(service, "tenantScope", tenantScope);
    }

    private CarrierAccountRef accountWithTenant(Long id, String customerNo) {
        CarrierAccountRef account = new CarrierAccountRef();
        account.setId(id);
        account.setCarrierCode("UPS");
        account.setAccountNumber("A12345");
        account.setCustomerNo(customerNo);
        account.setClientId("cid");
        account.setClientSecret("csec");
        account.setEnvironment("SANDBOX");
        account.setActive(true);
        return account;
    }

    /* -------------------------- verifyAccount -------------------------- */

    @Test
    void verifyAccount_scopedUserOnForeignAccount_isDenied() {
        CarrierAccountRef foreign = accountWithTenant(7L, "OTHER");
        when(accountRepo.findById(7L)).thenReturn(Optional.of(foreign));
        doThrow(new AccessDeniedException("cross tenant"))
                .when(tenantScope).requireTenantMatch("OTHER");

        assertThrows(AccessDeniedException.class, () -> service.verifyAccount(7L));
        verify(accountRepo, never()).save(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void verifyAccount_operatorOrMatchingTenant_reachesCarrier() {
        CarrierAccountRef own = accountWithTenant(9L, "ACME");
        when(accountRepo.findById(9L)).thenReturn(Optional.of(own));
        // requireTenantMatch("ACME") is silent (operator or matching tenant).
        CarrierConnector connector = mock(CarrierConnector.class);
        when(connector.getCarrierName()).thenReturn("UPS");
        when(connector.getAccessToken(anyString(), anyString(), any(), any()))
                .thenReturn("live-tok");
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);

        ApiResponse<CarrierAccountRefDTO> resp = service.verifyAccount(9L);

        assertEquals("success", resp.getStatus());
        verify(tenantScope).requireTenantMatch("ACME");
        verify(accountRepo).save(own);
    }

    /* -------------------------- setClientDefault -------------------------- */

    @Test
    void setClientDefault_scopedUserOnForeignAccount_isDenied() {
        CarrierAccountRef foreign = accountWithTenant(7L, "OTHER");
        when(accountRepo.findById(7L)).thenReturn(Optional.of(foreign));
        doThrow(new AccessDeniedException("cross tenant"))
                .when(tenantScope).requireTenantMatch("OTHER");

        assertThrows(AccessDeniedException.class, () -> service.setClientDefault(7L));
        verify(accountRepo, never()).save(any());
    }

    @Test
    void setClientDefault_operatorOrMatchingTenant_passesRow() {
        CarrierAccountRef own = accountWithTenant(9L, "ACME");
        when(accountRepo.findById(9L)).thenReturn(Optional.of(own));
        when(accountRepo.findByCustomerNoIgnoreCaseAndClientDefaultTrue("ACME"))
                .thenReturn(List.of());

        ApiResponse<CarrierAccountRefDTO> resp = service.setClientDefault(9L);

        assertEquals("success", resp.getStatus());
        verify(tenantScope).requireTenantMatch("ACME");
        verify(accountRepo).save(own);
    }

    /* -------------------------- toggleActive -------------------------- */

    @Test
    void toggleActive_scopedUserOnForeignAccount_isDenied() {
        CarrierAccountRef foreign = accountWithTenant(7L, "OTHER");
        when(accountRepo.findById(7L)).thenReturn(Optional.of(foreign));
        doThrow(new AccessDeniedException("cross tenant"))
                .when(tenantScope).requireTenantMatch("OTHER");

        assertThrows(AccessDeniedException.class, () -> service.toggleActive(7L));
        verify(accountRepo, never()).save(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void toggleActive_operatorOrMatchingTenant_flipsAndSaves() {
        CarrierAccountRef own = accountWithTenant(9L, "ACME");
        when(accountRepo.findById(9L)).thenReturn(Optional.of(own));

        ApiResponse<CarrierAccountRefDTO> resp = service.toggleActive(9L);

        assertEquals("success", resp.getStatus());
        verify(tenantScope).requireTenantMatch("ACME");
        verify(accountRepo).save(own);
    }

    /* -------------------------- deleteAccount -------------------------- */

    @Test
    void deleteAccount_scopedUserOnForeignAccount_isDenied() {
        CarrierAccountRef foreign = accountWithTenant(7L, "OTHER");
        when(accountRepo.findById(7L)).thenReturn(Optional.of(foreign));
        doThrow(new AccessDeniedException("cross tenant"))
                .when(tenantScope).requireTenantMatch("OTHER");

        assertThrows(AccessDeniedException.class, () -> service.deleteAccount(7L));
        verify(accountRepo, never()).delete(any());
    }

    @Test
    void deleteAccount_operatorOrMatchingTenant_deletesRow() {
        CarrierAccountRef own = accountWithTenant(9L, "ACME");
        when(accountRepo.findById(9L)).thenReturn(Optional.of(own));
        when(trackingRepo.countByAccountNumberIgnoreCaseAndIsLabelGeneratedTrue(anyString()))
                .thenReturn(0L);

        ApiResponse<Void> resp = service.deleteAccount(9L);

        assertEquals("success", resp.getStatus());
        verify(tenantScope).requireTenantMatch("ACME");
        verify(accountRepo).delete(own);
    }

    /* -------------------------- upsertAccount -------------------------- */

    @Test
    void upsertAccount_scopedUserWithForeignCustomerNo_isRejectedAtClamp() {
        AccountRefUpsertRequest req = AccountRefUpsertRequest.builder()
                .accountNumber("A12345")
                .carrierCode("UPS")
                .clientId("cid")
                .clientSecret("csec")
                .customerNo("OTHER")
                .build();
        // carrierService.getCarrierConnector is invoked BEFORE the clamp
        // (to canonicalise the carrier code); stub it so we reach the clamp.
        CarrierConnector connector = mock(CarrierConnector.class);
        when(connector.getCarrierCode()).thenReturn("UPS");
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);
        // The upsert path's first tenant call is clamp on request.customerNo.
        when(tenantScope.clampClientCode("OTHER"))
                .thenThrow(new AccessDeniedException("cross tenant"));

        assertThrows(AccessDeniedException.class, () -> service.upsertAccount(req));
        verify(accountRepo, never()).save(any());
    }

    @Test
    void upsertAccount_naturalKeyHijack_rejectsWhenExistingRowBelongsToDifferentTenant() {
        AccountRefUpsertRequest req = AccountRefUpsertRequest.builder()
                .accountNumber("A12345")
                .carrierCode("UPS")
                .clientId("cid")
                .clientSecret("csec")
                .customerNo("ACME")
                .build();

        // Scoped USER as ACME — clamp is silent (returns the same code).
        when(tenantScope.clampClientCode("ACME")).thenReturn("ACME");
        CarrierConnector connector = mock(CarrierConnector.class);
        when(connector.getCarrierCode()).thenReturn("UPS");
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);
        // Existing row belongs to OTHER — the natural-key hijack guard must
        // refuse before any save happens.
        CarrierAccountRef existing = accountWithTenant(5L, "OTHER");
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase("A12345", "UPS"))
                .thenReturn(Optional.of(existing));

        assertThrows(AccessDeniedException.class, () -> service.upsertAccount(req));
        verify(accountRepo, never()).save(any());
    }

    @Test
    void upsertAccount_operatorOrMatchingTenant_persistsClampedCustomerNo() {
        AccountRefUpsertRequest req = AccountRefUpsertRequest.builder()
                .accountNumber("A9999")
                .carrierCode("UPS")
                .clientId("cid")
                .clientSecret("csec")
                .customerNo(null)
                .build();
        // Scoped USER as ACME — clamp on null returns their own tenant.
        when(tenantScope.clampClientCode(null)).thenReturn("ACME");
        CarrierConnector connector = mock(CarrierConnector.class);
        when(connector.getCarrierCode()).thenReturn("UPS");
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase("A9999", "UPS"))
                .thenReturn(Optional.empty());
        // save() returns whatever it receives.
        when(accountRepo.save(any(CarrierAccountRef.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<CarrierAccountRefDTO> resp = service.upsertAccount(req);

        assertEquals("success", resp.getStatus());
        ArgumentCaptor<CarrierAccountRef> captor = ArgumentCaptor.forClass(CarrierAccountRef.class);
        verify(accountRepo).save(captor.capture());
        // Clamped customerNo must land on the persisted row, upper-cased.
        assertNotNull(captor.getValue());
        assertEquals("ACME", captor.getValue().getCustomerNo());
    }
}
