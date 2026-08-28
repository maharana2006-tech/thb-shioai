package com.multiship.backend.service;

import com.multiship.backend.dto.AccountRefUpsertRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.CarrierAccountRefDTO;
import com.multiship.backend.dto.CredentialCheckDTO;
import com.multiship.backend.dto.PlatformCredentialsDTO;
import com.multiship.backend.dto.VerifyCredentialsRequest;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import com.multiship.backend.service.carriers.CarrierConnector;
import com.multiship.backend.service.events.CarrierConfigChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    /* ======================================================================
     * Gap-fill tests (test/carriers-be-svc-gap): non-tenant branches — the
     * NOT_FOUND / VALIDATION / audit / event-publish / credential-check /
     * platform-credentials paths not exercised by the tenant-scope suite.
     * ==================================================================== */

    /* -------------------------- verifyAccount gaps -------------------------- */

    @Test
    void verifyAccount_missingId_returnsNotFound_noCarrierCall() {
        when(accountRepo.findById(404L)).thenReturn(Optional.empty());

        ApiResponse<CarrierAccountRefDTO> resp = service.verifyAccount(404L);

        assertEquals("error", resp.getStatus());
        assertEquals(404, resp.getCode());
        assertEquals("ACCOUNT_NOT_FOUND", resp.getErrorCode());
        // Guard: no downstream call proves no fallback path bypasses the mock.
        verify(carrierService, never()).getCarrierConnector(anyString());
        verify(accountRepo, never()).save(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void verifyAccount_incompleteAccount_returnsBadRequest_noCarrierCall() {
        CarrierAccountRef incomplete = accountWithTenant(11L, "ACME");
        incomplete.setClientId(null); // isComplete() requires all three fields.
        when(accountRepo.findById(11L)).thenReturn(Optional.of(incomplete));

        ApiResponse<CarrierAccountRefDTO> resp = service.verifyAccount(11L);

        assertEquals("error", resp.getStatus());
        assertEquals(400, resp.getCode());
        assertEquals("ACCOUNT_INCOMPLETE", resp.getErrorCode());
        verify(carrierService, never()).getCarrierConnector(anyString());
        verify(accountRepo, never()).save(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void verifyAccount_connectorReturnsLocalToken_marksUnverifiedAndPublishesEvent() {
        CarrierAccountRef own = accountWithTenant(12L, "ACME");
        when(accountRepo.findById(12L)).thenReturn(Optional.of(own));
        CarrierConnector connector = mock(CarrierConnector.class);
        when(connector.getCarrierName()).thenReturn("UPS");
        // -local- substring signals fallback => verified=false.
        when(connector.getAccessToken(anyString(), anyString(), any(), any()))
                .thenReturn("UPS-local-abc");
        when(connector.consumeAuthFailureDetail()).thenReturn("invalid ClientId");
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);

        ApiResponse<CarrierAccountRefDTO> resp = service.verifyAccount(12L);

        assertEquals("success", resp.getStatus());
        assertNotNull(resp.getData());
        assertFalse(Boolean.TRUE.equals(resp.getData().getVerified()));
        assertTrue(resp.getMessage().contains("invalid ClientId"));
        // Every credential-check invocation MUST go through the mock connector.
        verify(connector).validateCredentials("cid", "csec");
        verify(connector).getAccessToken(eq("cid"), eq("csec"), eq("A12345"), eq("SANDBOX"));
        verify(connector).consumeAuthFailureDetail();
        verify(accountRepo).save(own);
        verify(publisher).publishEvent(any(CarrierConfigChangedEvent.class));
    }

    @Test
    void verifyAccount_connectorThrows_returnsVerificationFailedMessage() {
        CarrierAccountRef own = accountWithTenant(13L, "ACME");
        when(accountRepo.findById(13L)).thenReturn(Optional.of(own));
        CarrierConnector connector = mock(CarrierConnector.class);
        when(connector.getCarrierName()).thenReturn("UPS");
        when(connector.getAccessToken(anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("boom"));
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);

        ApiResponse<CarrierAccountRefDTO> resp = service.verifyAccount(13L);

        // runCredentialCheck swallows the exception into a friendly message.
        assertEquals("success", resp.getStatus());
        assertFalse(Boolean.TRUE.equals(resp.getData().getVerified()));
        assertTrue(resp.getMessage().startsWith("Verification failed:"));
        verify(accountRepo).save(own);
        verify(publisher).publishEvent(any(CarrierConfigChangedEvent.class));
    }

    /* -------------------------- verifyCredentials (stateless) -------------- */

    @Test
    void verifyCredentials_realToken_returnsVerifiedTrue_viaConnectorMock() {
        VerifyCredentialsRequest req = VerifyCredentialsRequest.builder()
                .carrierCode("UPS").clientId("cid").clientSecret("csec")
                .accountNumber("A12345").environment("PRODUCTION").build();
        CarrierConnector connector = mock(CarrierConnector.class);
        when(connector.getCarrierName()).thenReturn("UPS");
        when(connector.getAccessToken(anyString(), anyString(), any(), any()))
                .thenReturn("real-live-token");
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);

        ApiResponse<CredentialCheckDTO> resp = service.verifyCredentials(req);

        assertEquals("success", resp.getStatus());
        assertTrue(resp.getData().getVerified());
        assertTrue(resp.getData().getMessage().contains("issued a live token"));
        // Mock-only path — no repo interaction should happen.
        verify(accountRepo, never()).save(any());
        verify(publisher, never()).publishEvent(any());
        verify(connector).validateCredentials("cid", "csec");
    }

    /* -------------------------- setClientDefault gaps ---------------------- */

    @Test
    void setClientDefault_missingId_returnsNotFound() {
        when(accountRepo.findById(404L)).thenReturn(Optional.empty());

        ApiResponse<CarrierAccountRefDTO> resp = service.setClientDefault(404L);

        assertEquals("error", resp.getStatus());
        assertEquals("ACCOUNT_NOT_FOUND", resp.getErrorCode());
        verify(accountRepo, never()).save(any());
    }

    @Test
    void setClientDefault_blankCustomerNo_returnsValidationError() {
        CarrierAccountRef platform = accountWithTenant(15L, ""); // platform row.
        when(accountRepo.findById(15L)).thenReturn(Optional.of(platform));

        ApiResponse<CarrierAccountRefDTO> resp = service.setClientDefault(15L);

        assertEquals("error", resp.getStatus());
        assertEquals(400, resp.getCode());
        assertEquals("VALIDATION_ERROR", resp.getErrorCode());
        verify(accountRepo, never()).save(any());
    }

    @Test
    void setClientDefault_incompleteCredentials_returnsBadRequest() {
        CarrierAccountRef own = accountWithTenant(16L, "ACME");
        own.setClientSecret(null); // isComplete() -> false.
        when(accountRepo.findById(16L)).thenReturn(Optional.of(own));

        ApiResponse<CarrierAccountRefDTO> resp = service.setClientDefault(16L);

        assertEquals("error", resp.getStatus());
        assertEquals("ACCOUNT_INCOMPLETE", resp.getErrorCode());
        verify(accountRepo, never()).save(any());
    }

    @Test
    void setClientDefault_demotesSameCarrierPeers_leavesOtherCarrierUntouched() {
        CarrierAccountRef target = accountWithTenant(20L, "ACME");
        target.setCarrierCode("UPS");
        CarrierAccountRef sameCarrierPeer = accountWithTenant(21L, "ACME");
        sameCarrierPeer.setCarrierCode("UPS");
        sameCarrierPeer.setClientDefault(true);
        CarrierAccountRef otherCarrierPeer = accountWithTenant(22L, "ACME");
        otherCarrierPeer.setCarrierCode("FEDEX");
        otherCarrierPeer.setClientDefault(true);

        when(accountRepo.findById(20L)).thenReturn(Optional.of(target));
        when(accountRepo.findByCustomerNoIgnoreCaseAndClientDefaultTrue("ACME"))
                .thenReturn(List.of(sameCarrierPeer, otherCarrierPeer));

        ApiResponse<CarrierAccountRefDTO> resp = service.setClientDefault(20L);

        assertEquals("success", resp.getStatus());
        // Peer on the SAME carrier is demoted.
        assertFalse(Boolean.TRUE.equals(sameCarrierPeer.getClientDefault()));
        // Peer on a DIFFERENT carrier survives — one default per (client,carrier).
        assertTrue(Boolean.TRUE.equals(otherCarrierPeer.getClientDefault()));
        verify(accountRepo).save(sameCarrierPeer);
        verify(accountRepo, never()).save(otherCarrierPeer);
        verify(accountRepo).save(target);
    }

    /* -------------------------- toggleActive gaps -------------------------- */

    @Test
    void toggleActive_missingId_returnsNotFound() {
        when(accountRepo.findById(404L)).thenReturn(Optional.empty());

        ApiResponse<CarrierAccountRefDTO> resp = service.toggleActive(404L);

        assertEquals("error", resp.getStatus());
        assertEquals("ACCOUNT_NOT_FOUND", resp.getErrorCode());
        verify(accountRepo, never()).save(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void toggleActive_activeAccount_deactivatesAndPublishesDeactivatedEvent() {
        CarrierAccountRef own = accountWithTenant(30L, "ACME");
        own.setActive(true); // currently active -> should flip to false.
        when(accountRepo.findById(30L)).thenReturn(Optional.of(own));

        ApiResponse<CarrierAccountRefDTO> resp = service.toggleActive(30L);

        assertEquals("success", resp.getStatus());
        assertFalse(own.getActive());
        assertTrue(resp.getMessage().contains("deactivated"));
        // Audit call must fire with TOGGLE_ACTIVE + CARRIER_ACCOUNT.
        verify(auditService).record(eq(AuditService.TOGGLE_ACTIVE),
                eq(AuditService.CARRIER_ACCOUNT),
                eq(30L), anyString(), any(), anyString());
        // Rate-cache invalidation event must be published.
        ArgumentCaptor<CarrierConfigChangedEvent> evt =
                ArgumentCaptor.forClass(CarrierConfigChangedEvent.class);
        verify(publisher).publishEvent(evt.capture());
        // Guard: no CarrierConnector call on a pure toggle.
        verify(carrierService, never()).getCarrierConnector(anyString());
    }

    /* -------------------------- deleteAccount gaps ------------------------- */

    @Test
    void deleteAccount_missingId_returnsNotFound() {
        when(accountRepo.findById(404L)).thenReturn(Optional.empty());

        ApiResponse<Void> resp = service.deleteAccount(404L);

        assertEquals("error", resp.getStatus());
        assertEquals("ACCOUNT_NOT_FOUND", resp.getErrorCode());
        verify(accountRepo, never()).delete(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void deleteAccount_hasLabels_returnsConflict_noDelete() {
        CarrierAccountRef own = accountWithTenant(40L, "ACME");
        when(accountRepo.findById(40L)).thenReturn(Optional.of(own));
        when(trackingRepo.countByAccountNumberIgnoreCaseAndIsLabelGeneratedTrue("A12345"))
                .thenReturn(3L);

        ApiResponse<Void> resp = service.deleteAccount(40L);

        assertEquals("error", resp.getStatus());
        assertEquals(409, resp.getCode());
        assertEquals("ACCOUNT_HAS_LABELS", resp.getErrorCode());
        verify(accountRepo, never()).delete(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void deleteAccount_success_publishesDeletedEventAndAudits() {
        CarrierAccountRef own = accountWithTenant(41L, "ACME");
        when(accountRepo.findById(41L)).thenReturn(Optional.of(own));
        when(trackingRepo.countByAccountNumberIgnoreCaseAndIsLabelGeneratedTrue("A12345"))
                .thenReturn(0L);

        ApiResponse<Void> resp = service.deleteAccount(41L);

        assertEquals("success", resp.getStatus());
        verify(accountRepo).delete(own);
        verify(auditService).record(eq(AuditService.DELETE),
                eq(AuditService.CARRIER_ACCOUNT),
                eq(41L), anyString(), any(), anyString());
        verify(publisher).publishEvent(any(CarrierConfigChangedEvent.class));
    }

    /* -------------------------- upsertAccount gaps ------------------------- */

    @Test
    void upsertAccount_newAccountMissingCredentials_returnsUnprocessable() {
        AccountRefUpsertRequest req = AccountRefUpsertRequest.builder()
                .accountNumber("A-NEW")
                .carrierCode("UPS")
                .clientId(null)          // <-- missing
                .clientSecret(null)      // <-- missing
                .customerNo("ACME")
                .build();
        when(tenantScope.clampClientCode("ACME")).thenReturn("ACME");
        CarrierConnector connector = mock(CarrierConnector.class);
        when(connector.getCarrierCode()).thenReturn("UPS");
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase("A-NEW", "UPS"))
                .thenReturn(Optional.empty());

        ApiResponse<CarrierAccountRefDTO> resp = service.upsertAccount(req);

        assertEquals("error", resp.getStatus());
        assertEquals(422, resp.getCode());
        assertEquals("VALIDATION_ERROR", resp.getErrorCode());
        verify(accountRepo, never()).save(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void upsertAccount_credentialsChanged_clearsVerifiedStamps() {
        AccountRefUpsertRequest req = AccountRefUpsertRequest.builder()
                .accountNumber("A12345")
                .carrierCode("UPS")
                .clientId("new-cid")
                .clientSecret("new-csec")
                .customerNo("ACME")
                .build();
        when(tenantScope.clampClientCode("ACME")).thenReturn("ACME");
        CarrierConnector connector = mock(CarrierConnector.class);
        when(connector.getCarrierCode()).thenReturn("UPS");
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);
        // Existing row (same tenant) with a stale verified stamp.
        CarrierAccountRef existing = accountWithTenant(50L, "ACME");
        existing.setVerified(true);
        existing.setLastVerifiedAt(LocalDateTime.of(2024, 1, 1, 0, 0));
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase("A12345", "UPS"))
                .thenReturn(Optional.of(existing));
        when(accountRepo.save(any(CarrierAccountRef.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<CarrierAccountRefDTO> resp = service.upsertAccount(req);

        assertEquals("success", resp.getStatus());
        // Fresh credentials MUST invalidate prior verification.
        assertNull(existing.getVerified());
        assertNull(existing.getLastVerifiedAt());
        assertEquals("new-cid", existing.getClientId());
        assertEquals("new-csec", existing.getClientSecret());
        verify(auditService).record(eq(AuditService.UPDATE),
                eq(AuditService.CARRIER_ACCOUNT),
                any(), anyString(), any(), anyString());
        verify(publisher).publishEvent(any(CarrierConfigChangedEvent.class));
    }

    /* -------------------------- getPlatformCredentials --------------------- */

    @Test
    void getPlatformCredentials_unknownCarrier_returnsFoundFalseWithoutQuery() {
        when(carrierService.getCarrierConnector("BOGUS"))
                .thenThrow(new IllegalArgumentException("no such carrier"));

        ApiResponse<PlatformCredentialsDTO> resp = service.getPlatformCredentials("BOGUS");

        assertEquals("success", resp.getStatus());
        assertFalse(resp.getData().isFound());
        // No repo hit when the carrier itself is unknown.
        verify(accountRepo, never()).findPlatformAccountsByCarrier(anyString());
    }

    @Test
    void getPlatformCredentials_found_returnsMaskedSecretNeverPlaintext() {
        CarrierConnector connector = mock(CarrierConnector.class);
        when(connector.getCarrierCode()).thenReturn("UPS");
        when(carrierService.getCarrierConnector("UPS")).thenReturn(connector);
        CarrierAccountRef platform = accountWithTenant(60L, null);
        platform.setClientId("client-1234567890");
        platform.setClientSecret("supersecretvalueXYZ9");
        when(accountRepo.findPlatformAccountsByCarrier("UPS")).thenReturn(List.of(platform));

        ApiResponse<PlatformCredentialsDTO> resp = service.getPlatformCredentials("UPS");

        assertEquals("success", resp.getStatus());
        assertTrue(resp.getData().isFound());
        assertTrue(resp.getData().isHasClientSecret());
        // Contract: never leak the plaintext secret; only masked "****" + last 4.
        String masked = resp.getData().getClientSecretMasked();
        assertTrue(masked.startsWith("****"));
        assertTrue(masked.endsWith("XYZ9"));
        assertFalse(masked.contains("supersecretvalue"));
    }

    @Test
    void getPlatformCredentials_noPlatformAccount_returnsFoundFalse() {
        CarrierConnector connector = mock(CarrierConnector.class);
        when(connector.getCarrierCode()).thenReturn("FEDEX");
        when(carrierService.getCarrierConnector("FEDEX")).thenReturn(connector);
        when(accountRepo.findPlatformAccountsByCarrier("FEDEX")).thenReturn(List.of());

        ApiResponse<PlatformCredentialsDTO> resp = service.getPlatformCredentials("FEDEX");

        assertEquals("success", resp.getStatus());
        assertFalse(resp.getData().isFound());
        assertEquals("FEDEX", resp.getData().getCarrierCode());
        verify(accountRepo, times(1)).findPlatformAccountsByCarrier("FEDEX");
    }

    /* -------------------------- listSyncEligibleAccounts -------------------------- */

    @Test
    void listSyncEligible_blankCarrier_isRejected422() {
        ApiResponse<com.multiship.backend.dto.SyncEligibleAccountDTO> resp422 =
                (ApiResponse) service.listSyncEligibleAccounts("");
        assertEquals(422, resp422.getCode());
        verify(accountRepo, never()).findVerifiedActiveByCarrier(any());
    }

    @Test
    void listSyncEligible_returnsProjectedRowsWithPlatformFlag() {
        // Sprint 51 catalog sync — one platform + one client account both
        // survive the verified+active filter. isPlatform derived from
        // customerNo null/blank.
        CarrierAccountRef platform = accountWithTenant(11L, null);
        platform.setAccountNumber("A-PLATFORM");
        platform.setAccountName("Platform UPS");
        platform.setVerified(true);
        CarrierAccountRef client = accountWithTenant(12L, "ACME01");
        client.setAccountNumber("A-CLIENT");
        client.setAccountName("Acme UPS Prod");
        client.setEnvironment("PRODUCTION");
        client.setVerified(true);
        when(accountRepo.findVerifiedActiveByCarrier("UPS"))
                .thenReturn(List.of(platform, client));

        ApiResponse<List<com.multiship.backend.dto.SyncEligibleAccountDTO>> resp =
                service.listSyncEligibleAccounts("UPS");

        assertEquals("success", resp.getStatus());
        assertEquals(2, resp.getData().size());
        var p = resp.getData().get(0);
        assertEquals(11L, p.getId());
        assertTrue(p.getIsPlatform());
        assertNull(p.getCustomerNo(), "platform account exposes no customerNo");
        assertEquals("SANDBOX", p.getEnvironment());
        var c = resp.getData().get(1);
        assertEquals(12L, c.getId());
        assertFalse(c.getIsPlatform());
        assertEquals("ACME01", c.getCustomerNo());
        assertEquals("PRODUCTION", c.getEnvironment());
    }

    @Test
    void listSyncEligible_emptyResult_returnsSuccessAndHint() {
        when(accountRepo.findVerifiedActiveByCarrier("UPS")).thenReturn(List.of());

        ApiResponse<List<com.multiship.backend.dto.SyncEligibleAccountDTO>> resp =
                service.listSyncEligibleAccounts("UPS");

        assertEquals("success", resp.getStatus());
        assertTrue(resp.getData().isEmpty());
        assertTrue(resp.getMessage().contains("No verified"));
    }
}
