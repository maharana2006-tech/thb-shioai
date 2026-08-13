package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientBillingMarkupDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.UpdateClientMarkupRequest;
import com.multiship.backend.model.ClientBillingMarkup;
import com.multiship.backend.repository.ClientBillingMarkupRepository;
import com.multiship.backend.repository.ClientRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 T5b (audit finding #11) — coverage for the billing-markup
 * service. This is a money-touching path: an incorrect markup ships
 * every subsequent shipment at the wrong margin. Pre-T5b it had zero
 * unit tests.
 *
 * <p>Guards: happy get + happy update, missing client → 404, tenant
 * mismatch → 403 before the DB, validation → 400 with MARKUP_INVALID.
 */
class ClientBillingMarkupServiceImplTest {

    private ClientBillingMarkupRepository repo;
    private ClientRepository clientRepo;
    private ClientBillingMarkupServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(ClientBillingMarkupRepository.class);
        clientRepo = mock(ClientRepository.class);
        service = new ClientBillingMarkupServiceImpl(repo, clientRepo);
        // Enforcer with flag OFF is a pure pass-through; individual
        // tests that exercise the tenant guard flip it on locally.
        ReflectionTestUtils.setField(service, "tenantScope",
                new TenantScopeEnforcer(new AccessScopePolicy(false)));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    /* -------- happy paths -------- */

    @Test
    void getReturnsExistingMarkup() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        when(repo.findByClientCodeIgnoreCase("ACME")).thenReturn(Optional.of(
                ClientBillingMarkup.builder().clientCode("ACME")
                        .kind(ClientBillingMarkup.KIND_PERCENT)
                        .value(new BigDecimal("15.5"))
                        .currency("USD").build()));

        ApiResponse<ClientBillingMarkupDTO> resp = service.get("acme");

        assertEquals(200, resp.getCode());
        assertNotNull(resp.getData());
        assertEquals("ACME", resp.getData().getClientCode());
        assertEquals(new BigDecimal("15.5"), resp.getData().getValue());
    }

    @Test
    void getReturnsSyntheticZeroWhenNoRow() {
        // Absent row = zero-markup default (documented resolver behaviour).
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        when(repo.findByClientCodeIgnoreCase("ACME")).thenReturn(Optional.empty());

        ApiResponse<ClientBillingMarkupDTO> resp = service.get("ACME");

        assertEquals(200, resp.getCode());
        assertEquals(BigDecimal.ZERO, resp.getData().getValue());
        assertEquals("USD", resp.getData().getCurrency());
    }

    @Test
    void updateSavesAndReturnsNewMarkup() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        when(repo.findByClientCodeIgnoreCase("ACME")).thenReturn(Optional.empty());

        UpdateClientMarkupRequest req = new UpdateClientMarkupRequest();
        req.setKind("PERCENT");
        req.setValue(new BigDecimal("12.0"));
        req.setCurrency("USD");

        ApiResponse<ClientBillingMarkupDTO> resp = service.update("acme", req);

        assertEquals(200, resp.getCode());
        assertEquals(new BigDecimal("12.0"), resp.getData().getValue());
        verify(repo).save(any(ClientBillingMarkup.class));
    }

    /* -------- 404: missing parent client -------- */

    @Test
    void getReturns404WhenClientMissing() {
        when(clientRepo.existsByClientCodeIgnoreCase(anyString())).thenReturn(false);
        ApiResponse<ClientBillingMarkupDTO> resp = service.get("GHOST");
        assertEquals(404, resp.getCode());
        assertEquals(ErrorCode.CLIENT_NOT_FOUND.name(), resp.getErrorCode());
    }

    @Test
    void updateReturns404WhenClientMissing() {
        when(clientRepo.existsByClientCodeIgnoreCase(anyString())).thenReturn(false);
        UpdateClientMarkupRequest req = validRequest();
        ApiResponse<ClientBillingMarkupDTO> resp = service.update("GHOST", req);
        assertEquals(404, resp.getCode());
        assertEquals(ErrorCode.CLIENT_NOT_FOUND.name(), resp.getErrorCode());
        verify(repo, never()).save(any());
    }

    /* -------- 403: tenant-mismatch fires BEFORE any DB read -------- */

    @Test
    void scopedUserCannotReachForeignClientMarkup() {
        // Scoped USER for ACME hits /clients/OTHER/markup → 403 before DB.
        putScopedUser("acmeuser", "ACME");
        // Re-inject a flag-ON enforcer so the guard actually fires.
        ReflectionTestUtils.setField(service, "tenantScope",
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        assertThrows(AccessDeniedException.class, () -> service.get("OTHER"));
        assertThrows(AccessDeniedException.class,
                () -> service.update("OTHER", validRequest()));
        // Enforcer runs before existsByClientCodeIgnoreCase.
        verify(clientRepo, never()).existsByClientCodeIgnoreCase(anyString());
    }

    /* -------- 400: validation failures return MARKUP_INVALID -------- */

    @Test
    void updateRejectsInvalidKind() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        UpdateClientMarkupRequest req = validRequest();
        req.setKind("BOGUS");
        ApiResponse<ClientBillingMarkupDTO> resp = service.update("ACME", req);
        assertEquals(400, resp.getCode());
        assertEquals(ErrorCode.MARKUP_INVALID.name(), resp.getErrorCode());
    }

    @Test
    void updateRejectsNegativeValue() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        UpdateClientMarkupRequest req = validRequest();
        req.setValue(new BigDecimal("-1"));
        ApiResponse<ClientBillingMarkupDTO> resp = service.update("ACME", req);
        assertEquals(400, resp.getCode());
        assertEquals(ErrorCode.MARKUP_INVALID.name(), resp.getErrorCode());
    }

    @Test
    void updateRejectsInvalidCurrencyLength() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        UpdateClientMarkupRequest req = validRequest();
        req.setCurrency("US");  // 2 chars, not 3
        ApiResponse<ClientBillingMarkupDTO> resp = service.update("ACME", req);
        assertEquals(400, resp.getCode());
        assertEquals(ErrorCode.MARKUP_INVALID.name(), resp.getErrorCode());
    }

    /* -------- helpers -------- */

    private UpdateClientMarkupRequest validRequest() {
        UpdateClientMarkupRequest req = new UpdateClientMarkupRequest();
        req.setKind("PERCENT");
        req.setValue(new BigDecimal("10"));
        req.setCurrency("USD");
        return req;
    }

    private void putScopedUser(String username, String clientCode) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = User.withUsername(username).password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        token.setDetails(new JwtAuthenticationFilter.AuthDetails(clientCode));
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
