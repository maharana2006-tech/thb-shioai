package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.dto.AllowServiceRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientAllowedServiceDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.ClientAllowedService;
import com.multiship.backend.model.ShippingService;
import com.multiship.backend.repository.ClientAllowedServiceDestinationRepository;
import com.multiship.backend.repository.ClientAllowedServiceRepository;
import com.multiship.backend.repository.ClientAllowedServiceWarehouseRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientWarehouseRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 T5b (audit finding #11) — coverage for the allowed-service
 * gating service. Enforces which carrier services a client is allowed
 * to use (e.g. UPS Ground vs UPS Next Day). Pre-T5b: zero unit tests.
 */
class ClientAllowedServiceServiceImplTest {

    private ClientAllowedServiceRepository repo;
    private ClientAllowedServiceDestinationRepository destRepo;
    private ClientAllowedServiceWarehouseRepository whRepo;
    private ClientWarehouseRepository clientWhRepo;
    private ClientRepository clientRepo;
    private ShippingServiceRepository serviceRepo;
    private ClientAllowedServiceServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(ClientAllowedServiceRepository.class);
        destRepo = mock(ClientAllowedServiceDestinationRepository.class);
        whRepo = mock(ClientAllowedServiceWarehouseRepository.class);
        clientWhRepo = mock(ClientWarehouseRepository.class);
        clientRepo = mock(ClientRepository.class);
        serviceRepo = mock(ShippingServiceRepository.class);
        service = new ClientAllowedServiceServiceImpl(
                repo, destRepo, whRepo, clientWhRepo, clientRepo, serviceRepo);
        ReflectionTestUtils.setField(service, "tenantScope",
                new TenantScopeEnforcer(new AccessScopePolicy(false)));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void allowSavesNewLinkAsDefaultWhenFirst() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        ShippingService svc = new ShippingService();
        svc.setId(7L);
        svc.setServiceCode("UPS_GROUND");
        when(serviceRepo.findById(7L)).thenReturn(Optional.of(svc));
        when(repo.existsByClientCodeIgnoreCaseAndServiceId("ACME", 7L)).thenReturn(false);
        when(repo.findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of());

        AllowServiceRequest req = new AllowServiceRequest();
        req.setServiceId(7L);
        ApiResponse<ClientAllowedServiceDTO> resp = service.allow("acme", req);

        assertEquals(200, resp.getCode());
        verify(repo).save(any(ClientAllowedService.class));
    }

    @Test
    void allowReturns404WhenClientMissing() {
        when(clientRepo.existsByClientCodeIgnoreCase(anyString())).thenReturn(false);
        AllowServiceRequest req = new AllowServiceRequest();
        req.setServiceId(7L);
        ApiResponse<ClientAllowedServiceDTO> resp = service.allow("GHOST", req);
        assertEquals(404, resp.getCode());
        assertEquals(ErrorCode.CLIENT_NOT_FOUND.name(), resp.getErrorCode());
    }

    @Test
    void scopedUserCannotReachForeignClientAllowlist() {
        putScopedUser();
        ReflectionTestUtils.setField(service, "tenantScope",
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        assertThrows(AccessDeniedException.class, () -> service.listForClient("OTHER"));
        assertThrows(AccessDeniedException.class, () -> service.remove("OTHER", 7L));
        verify(clientRepo, never()).existsByClientCodeIgnoreCase(anyString());
    }

    @Test
    void allowReturns409WhenServiceAlreadyOnList() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        ShippingService svc = new ShippingService();
        svc.setId(7L);
        svc.setServiceCode("UPS_GROUND");
        when(serviceRepo.findById(7L)).thenReturn(Optional.of(svc));
        when(repo.existsByClientCodeIgnoreCaseAndServiceId("ACME", 7L)).thenReturn(true);

        AllowServiceRequest req = new AllowServiceRequest();
        req.setServiceId(7L);
        ApiResponse<ClientAllowedServiceDTO> resp = service.allow("ACME", req);
        assertEquals(409, resp.getCode());
        assertEquals(ErrorCode.ALLOWLIST_ALREADY_EXISTS.name(), resp.getErrorCode());
        verify(repo, never()).save(any(ClientAllowedService.class));
    }

    @Test
    void removeReturns404WhenLinkNotFound() {
        when(repo.findByClientCodeIgnoreCaseAndServiceId("ACME", 7L)).thenReturn(Optional.empty());
        ApiResponse<Void> resp = service.remove("ACME", 7L);
        assertEquals(404, resp.getCode());
        assertEquals(ErrorCode.ALLOWLIST_ENTRY_NOT_FOUND.name(), resp.getErrorCode());
        verify(repo, never()).delete(any());
    }

    private void putScopedUser() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = User.withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        token.setDetails(new JwtAuthenticationFilter.AuthDetails("ACME"));
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
