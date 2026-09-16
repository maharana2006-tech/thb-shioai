package com.multiship.backend.controller;

import com.multiship.backend.dto.UspsProviderReadinessDTO;
import com.multiship.backend.service.SystemSettingService;
import com.multiship.backend.service.UspsProviderReadinessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-A Agent-A — state-machine guard on
 * {@code PUT /api/v1/admin/system-settings/USPS_PROVIDER}.
 *
 * <p>The USPS_PROVIDER setting cycles through {@code STAMPS_COM →
 * PROVISIONING_USPS_DIRECT → USPS_DIRECT} (and back). This test locks in:
 *
 * <ul>
 *   <li>The 6 non-reflexive legal transitions (2 pairs each direction on the
 *       STAMPS_COM ↔ PROV edge and the PROV ↔ USPS_DIRECT edge) + 3 reflexive
 *       (X → X) = 9 accepted pairs plus 2 reflexives added for readability.</li>
 *   <li>Direct STAMPS_COM ↔ USPS_DIRECT is rejected 400 with an actionable
 *       error message.</li>
 *   <li>PROVISIONING → USPS_DIRECT with readiness=false returns 409 with the
 *       readiness DTO in the body so the FE can render what's missing.</li>
 *   <li>PROVISIONING → USPS_DIRECT with readiness=true returns 200.</li>
 * </ul>
 *
 * <p>Anti-fallback: the {@link SystemSettingService} + the
 * {@link UspsProviderReadinessService} are both mocked; no DB, no
 * connector, no real Spring context.
 */
class UspsProviderTransitionGuardTest {

    private static final String KEY = UspsProviderReadinessService.USPS_PROVIDER_KEY;
    private static final String STAMPS = "STAMPS_COM";
    private static final String PROV = "PROVISIONING_USPS_DIRECT";
    private static final String DIRECT = "USPS_DIRECT";

    private SystemSettingService service;
    private UspsProviderReadinessService uspsReadinessService;
    private SystemSettingsController controller;

    @BeforeEach
    void setUp() {
        service = mock(SystemSettingService.class);
        uspsReadinessService = mock(UspsProviderReadinessService.class);
        controller = new SystemSettingsController(service, uspsReadinessService);
    }

    // ============================ helpers ============================

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken(
                "admin-op", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    /** Stub the current stored USPS_PROVIDER value. */
    private void setCurrent(String value) {
        when(service.getDecrypted(KEY)).thenReturn(Optional.of(value));
    }

    /** Stub readiness so PROV → USPS_DIRECT is allowed / rejected. */
    private void setReadiness(boolean overallReady) {
        UspsProviderReadinessDTO dto = UspsProviderReadinessDTO.builder()
                .currentProvider(PROV)
                .targetProvider(DIRECT)
                .platformCreds(UspsProviderReadinessDTO.PlatformCreds.builder()
                        .clientIdSet(overallReady)
                        .clientSecretSet(overallReady)
                        .build())
                .totalUspsAccounts(overallReady ? 0 : 1)
                .readyAccounts(0)
                .pendingAccounts(overallReady ? List.of()
                        : List.of(UspsProviderReadinessDTO.PendingAccount.builder()
                                .tenantCode("ACME")
                                .accountNumber("A123")
                                .missing(List.of("usps_direct_crid", "usps_direct_mid"))
                                .build()))
                .overallReady(overallReady)
                .build();
        when(uspsReadinessService.check()).thenReturn(dto);
    }

    private ResponseEntity<?> put(String target) {
        return controller.update(KEY, Map.of("value", target), admin());
    }

    // ==================== LEGAL: reflexive (X → X) ====================

    @Test
    void reflexive_stamps_to_stamps_ok_and_setEncryptedCalled() {
        setCurrent(STAMPS);
        ResponseEntity<?> re = put(STAMPS);
        assertEquals(HttpStatus.OK, re.getStatusCode(),
                "X → X reflexive transitions must always be accepted.");
        verify(service, times(1)).setEncrypted(eq(KEY), eq(STAMPS), eq("admin-op"));
        verify(uspsReadinessService, never()).check();
    }

    @Test
    void reflexive_prov_to_prov_ok() {
        setCurrent(PROV);
        ResponseEntity<?> re = put(PROV);
        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).setEncrypted(eq(KEY), eq(PROV), eq("admin-op"));
    }

    @Test
    void reflexive_direct_to_direct_ok() {
        setCurrent(DIRECT);
        ResponseEntity<?> re = put(DIRECT);
        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).setEncrypted(eq(KEY), eq(DIRECT), eq("admin-op"));
    }

    // ==================== LEGAL: adjacent transitions ====================

    @Test
    void legal_stamps_to_prov_ok() {
        setCurrent(STAMPS);
        ResponseEntity<?> re = put(PROV);
        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).setEncrypted(eq(KEY), eq(PROV), eq("admin-op"));
        verify(uspsReadinessService, never()).check();
    }

    @Test
    void legal_prov_to_stamps_ok_noReadinessCheck() {
        setCurrent(PROV);
        ResponseEntity<?> re = put(STAMPS);
        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).setEncrypted(eq(KEY), eq(STAMPS), eq("admin-op"));
        verify(uspsReadinessService, never()).check();
    }

    @Test
    void legal_direct_to_prov_ok_noReadinessCheck() {
        setCurrent(DIRECT);
        ResponseEntity<?> re = put(PROV);
        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).setEncrypted(eq(KEY), eq(PROV), eq("admin-op"));
        verify(uspsReadinessService, never()).check();
    }

    // ==================== ILLEGAL: direct jump both ways ====================

    @Test
    void illegal_stamps_to_direct_rejected400_withActionableMessage() {
        setCurrent(STAMPS);
        ResponseEntity<?> re = put(DIRECT);

        assertEquals(HttpStatus.BAD_REQUEST, re.getStatusCode(),
                "STAMPS_COM → USPS_DIRECT skips the readiness handshake — must be 400.");
        assertNotNull(re.getBody());
        assertTrue(re.getBody() instanceof Map,
                "Error body must be JSON-shaped so the FE can render it.");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) re.getBody();
        assertEquals("must transit via PROVISIONING_USPS_DIRECT", body.get("error"),
                "Error message must tell the operator exactly what to do.");
        verify(service, never()).setEncrypted(eq(KEY), eq(DIRECT), eq("admin-op"));
    }

    @Test
    void illegal_direct_to_stamps_rejected400_withActionableMessage() {
        setCurrent(DIRECT);
        ResponseEntity<?> re = put(STAMPS);

        assertEquals(HttpStatus.BAD_REQUEST, re.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) re.getBody();
        assertEquals("must transit via PROVISIONING_USPS_DIRECT", body.get("error"));
        verify(service, never()).setEncrypted(eq(KEY), eq(STAMPS), eq("admin-op"));
    }

    // ==================== READINESS: PROV → USPS_DIRECT ====================

    @Test
    void prov_to_direct_readinessFalse_returns409_withDtoBody() {
        setCurrent(PROV);
        setReadiness(false);

        ResponseEntity<?> re = put(DIRECT);

        assertEquals(HttpStatus.CONFLICT, re.getStatusCode(),
                "PROVISIONING → USPS_DIRECT when readiness fails must be 409, not 400 — "
                        + "the request is well-formed, the resource state is wrong.");
        assertNotNull(re.getBody());
        assertTrue(re.getBody() instanceof UspsProviderReadinessDTO,
                "409 body must be the readiness DTO so the FE can render what's missing.");
        UspsProviderReadinessDTO dto = (UspsProviderReadinessDTO) re.getBody();
        assertFalse(dto.isOverallReady());
        assertEquals(DIRECT, dto.getTargetProvider());
        assertEquals(1, dto.getPendingAccounts().size());
        verify(uspsReadinessService, times(1)).check();
        verify(service, never()).setEncrypted(eq(KEY), eq(DIRECT), eq("admin-op"));
    }

    @Test
    void prov_to_direct_readinessTrue_returns200() {
        setCurrent(PROV);
        setReadiness(true);

        ResponseEntity<?> re = put(DIRECT);

        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(uspsReadinessService, times(1)).check();
        verify(service, times(1)).setEncrypted(eq(KEY), eq(DIRECT), eq("admin-op"));
    }

    // ==================== EDGE: current setting unset ====================

    @Test
    void unsetCurrent_defaultsToStamps_prov_target_allowed() {
        // No stored value → default = STAMPS_COM, so STAMPS_COM → PROV is legal.
        when(service.getDecrypted(KEY)).thenReturn(Optional.empty());
        ResponseEntity<?> re = put(PROV);
        assertEquals(HttpStatus.OK, re.getStatusCode());
        verify(service, times(1)).setEncrypted(eq(KEY), eq(PROV), eq("admin-op"));
    }

    @Test
    void unsetCurrent_defaultsToStamps_direct_target_rejected400() {
        // No stored value → default = STAMPS_COM, so STAMPS_COM → USPS_DIRECT is illegal.
        when(service.getDecrypted(KEY)).thenReturn(Optional.empty());
        ResponseEntity<?> re = put(DIRECT);
        assertEquals(HttpStatus.BAD_REQUEST, re.getStatusCode());
        verify(service, never()).setEncrypted(eq(KEY), eq(DIRECT), eq("admin-op"));
    }

    // ==================== EDGE: option-list validation still runs first ====================

    @Test
    void invalidValue_rejected400_beforeGuardEvenLooksAtIt() {
        setCurrent(STAMPS);
        ResponseEntity<?> re = controller.update(KEY, Map.of("value", "PIGEON_POST"), admin());
        assertEquals(HttpStatus.BAD_REQUEST, re.getStatusCode(),
                "CHOICE-option validation runs before the transition guard.");
        verify(uspsReadinessService, never()).check();
        verify(service, never()).setEncrypted(eq(KEY), eq("PIGEON_POST"), eq("admin-op"));
    }
}
