package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientDestinationRulesDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.ReplaceDestinationRulesRequest;
import com.multiship.backend.model.ClientDestinationRule;
import com.multiship.backend.repository.ClientDestinationRuleRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 T5b (audit finding #11) — coverage for the destination-rule
 * service. Enforces which countries a client is allowed to ship to
 * (ALLOW/DENY mode). Pre-T5b: zero unit tests.
 */
class ClientDestinationRuleServiceImplTest {

    private ClientDestinationRuleRepository repo;
    private ClientRepository clientRepo;
    private ClientDestinationRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(ClientDestinationRuleRepository.class);
        clientRepo = mock(ClientRepository.class);
        service = new ClientDestinationRuleServiceImpl(repo, clientRepo);
        ReflectionTestUtils.setField(service, "tenantScope",
                new TenantScopeEnforcer(new AccessScopePolicy(false)));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void getReturnsRulesForClient() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        when(repo.findByClientCodeIgnoreCaseOrderByCountryAsc("ACME")).thenReturn(List.of(
                ClientDestinationRule.builder().clientCode("ACME").mode("ALLOW").country("US").build(),
                ClientDestinationRule.builder().clientCode("ACME").mode("ALLOW").country("CA").build()));

        ApiResponse<ClientDestinationRulesDTO> resp = service.get("acme");
        assertEquals(200, resp.getCode());
        assertEquals("ALLOW", resp.getData().getMode());
        assertEquals(2, resp.getData().getCountries().size());
    }

    @Test
    void getReturns404WhenClientMissing() {
        when(clientRepo.existsByClientCodeIgnoreCase(anyString())).thenReturn(false);
        ApiResponse<ClientDestinationRulesDTO> resp = service.get("GHOST");
        assertEquals(404, resp.getCode());
        assertEquals(ErrorCode.CLIENT_NOT_FOUND.name(), resp.getErrorCode());
    }

    @Test
    void scopedUserCannotReachForeignClientRules() {
        putScopedUser();
        ReflectionTestUtils.setField(service, "tenantScope",
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        assertThrows(AccessDeniedException.class, () -> service.get("OTHER"));
        assertThrows(AccessDeniedException.class, () -> service.clear("OTHER"));
        verify(clientRepo, never()).existsByClientCodeIgnoreCase(anyString());
    }

    @Test
    void replaceRejectsInvalidMode() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        ReplaceDestinationRulesRequest req = new ReplaceDestinationRulesRequest();
        req.setMode("BOGUS");
        req.setCountries(List.of("US"));
        ApiResponse<ClientDestinationRulesDTO> resp = service.replace("ACME", req);
        assertEquals(400, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
        verify(repo, never()).save(any());
    }

    private void putScopedUser() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = User.withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        token.setDetails(new JwtAuthenticationFilter.AuthDetails("ACME"));
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
