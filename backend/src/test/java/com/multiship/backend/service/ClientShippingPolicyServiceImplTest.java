package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientShippingPolicyDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.UpdateClientPolicyRequest;
import com.multiship.backend.model.ClientShippingPolicy;
import com.multiship.backend.repository.ClientAllowedServiceRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientShippingPolicyRepository;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 T5b (audit finding #11) — coverage for the shipping-policy
 * service. Governs the client's default rate strategy (CHEAPEST /
 * FASTEST / FIXED). FIXED requires the pinned service to also be on
 * the allowlist, or shipment resolution would try to use a service
 * the client can't ship with.
 */
class ClientShippingPolicyServiceImplTest {

    private ClientShippingPolicyRepository repo;
    private ClientRepository clientRepo;
    private ClientAllowedServiceRepository allowedServiceRepo;
    private ClientShippingPolicyServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(ClientShippingPolicyRepository.class);
        clientRepo = mock(ClientRepository.class);
        allowedServiceRepo = mock(ClientAllowedServiceRepository.class);
        service = new ClientShippingPolicyServiceImpl(repo, clientRepo, allowedServiceRepo);
        ReflectionTestUtils.setField(service, "tenantScope",
                new TenantScopeEnforcer(new AccessScopePolicy(false)));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void getReturnsSyntheticCheapestWhenNoRow() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        when(repo.findByClientCodeIgnoreCase("ACME")).thenReturn(Optional.empty());
        ApiResponse<ClientShippingPolicyDTO> resp = service.get("ACME");
        assertEquals(200, resp.getCode());
        assertNotNull(resp.getData());
        assertEquals(ClientShippingPolicy.STRATEGY_CHEAPEST, resp.getData().getRateStrategy());
    }

    @Test
    void getReturns404WhenClientMissing() {
        when(clientRepo.existsByClientCodeIgnoreCase(anyString())).thenReturn(false);
        ApiResponse<ClientShippingPolicyDTO> resp = service.get("GHOST");
        assertEquals(404, resp.getCode());
        assertEquals(ErrorCode.CLIENT_NOT_FOUND.name(), resp.getErrorCode());
    }

    @Test
    void scopedUserCannotReachForeignClientPolicy() {
        putScopedUser();
        ReflectionTestUtils.setField(service, "tenantScope",
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        assertThrows(AccessDeniedException.class, () -> service.get("OTHER"));
        UpdateClientPolicyRequest req = new UpdateClientPolicyRequest();
        req.setRateStrategy("CHEAPEST");
        assertThrows(AccessDeniedException.class, () -> service.update("OTHER", req));
        verify(clientRepo, never()).existsByClientCodeIgnoreCase(anyString());
    }

    @Test
    void updateRejectsInvalidStrategy() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        UpdateClientPolicyRequest req = new UpdateClientPolicyRequest();
        req.setRateStrategy("BOGUS");
        ApiResponse<ClientShippingPolicyDTO> resp = service.update("ACME", req);
        assertEquals(400, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
    }

    @Test
    void updateRejectsFixedStrategyWithoutFixedServiceId() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        UpdateClientPolicyRequest req = new UpdateClientPolicyRequest();
        req.setRateStrategy("FIXED");
        // No fixedServiceId set
        ApiResponse<ClientShippingPolicyDTO> resp = service.update("ACME", req);
        assertEquals(400, resp.getCode());
        assertEquals(ErrorCode.POLICY_FIXED_SERVICE_REQUIRED.name(), resp.getErrorCode());
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
