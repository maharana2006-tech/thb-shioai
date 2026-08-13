package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientCustomsProfileDTO;
import com.multiship.backend.model.ClientCustomsProfile;
import com.multiship.backend.repository.ClientCustomsProfileRepository;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 T5b (audit finding #11) — coverage for the customs-profile
 * service. Owns per-tenant importer/broker records used on international
 * shipment paperwork. A misassigned profile silently leaks the wrong
 * client's tax ID onto a commercial invoice, hence tenant scope is
 * doubly-guarded.
 */
class ClientCustomsProfileServiceImplTest {

    private ClientCustomsProfileRepository repo;
    private ClientRepository clientRepo;
    private ClientCustomsProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(ClientCustomsProfileRepository.class);
        clientRepo = mock(ClientRepository.class);
        service = new ClientCustomsProfileServiceImpl(repo, clientRepo);
        ReflectionTestUtils.setField(service, "tenantScope",
                new TenantScopeEnforcer(new AccessScopePolicy(false)));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void listReturnsClientProfiles() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        when(repo.findByClientCodeIgnoreCase("ACME")).thenReturn(List.of(
                ClientCustomsProfile.builder().id(1L).clientCode("ACME").build()));

        ApiResponse<List<ClientCustomsProfileDTO>> resp = service.list("acme");
        assertEquals(200, resp.getCode());
        assertEquals(1, resp.getData().size());
    }

    @Test
    void listReturns404WhenClientMissing() {
        when(clientRepo.existsByClientCodeIgnoreCase(anyString())).thenReturn(false);
        ApiResponse<List<ClientCustomsProfileDTO>> resp = service.list("GHOST");
        assertEquals(404, resp.getCode());
    }

    @Test
    void scopedUserCannotReachForeignClientProfiles() {
        putScopedUser();
        ReflectionTestUtils.setField(service, "tenantScope",
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        assertThrows(AccessDeniedException.class, () -> service.list("OTHER"));
        assertThrows(AccessDeniedException.class, () -> service.get("OTHER", 1L));
        assertThrows(AccessDeniedException.class, () -> service.delete("OTHER", 1L));
        verify(clientRepo, never()).existsByClientCodeIgnoreCase(anyString());
    }

    @Test
    void getForeignProfileIdReturnsNullData() {
        // Cross-tenant id: profile belongs to OTHER but caller passes
        // ACME. The service filters by clientCode match, so the profile
        // is treated as not-found.
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        when(repo.findById(99L)).thenReturn(Optional.of(
                ClientCustomsProfile.builder().id(99L).clientCode("OTHER").build()));

        ApiResponse<ClientCustomsProfileDTO> resp = service.get("ACME", 99L);
        assertEquals(200, resp.getCode());
        // "No such profile" — treated as absent for the ACME caller,
        // even though the row exists under OTHER's clientCode.
        assertEquals(null, resp.getData());
    }

    private void putScopedUser() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = User.withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        token.setDetails(new JwtAuthenticationFilter.AuthDetails("ACME"));
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
