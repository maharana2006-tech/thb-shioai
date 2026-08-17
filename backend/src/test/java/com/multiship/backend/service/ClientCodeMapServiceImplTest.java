package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientCodeMapDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.ClientShipviaCodeMap;
import com.multiship.backend.repository.ClientDestCountryMapRepository;
import com.multiship.backend.repository.ClientPackageCodeMapRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientServiceCodeMapRepository;
import com.multiship.backend.repository.ClientShipviaCodeMapRepository;
import com.multiship.backend.repository.PackagePresetRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 50 Tier 0.5 PR E — locks in the Pattern B tenant guard on
 * every path-param clientCode. A scoped USER hitting a foreign
 * client's code-map endpoints must get a 403 before the DB is
 * consulted.
 */
class ClientCodeMapServiceImplTest {

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void scopedUserCannotReachForeignTenantCodeMaps() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = User.withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        token.setDetails(new JwtAuthenticationFilter.AuthDetails("ACME"));
        SecurityContextHolder.getContext().setAuthentication(token);

        ClientCodeMapServiceImpl service = new ClientCodeMapServiceImpl(
                mock(ClientRepository.class),
                mock(ClientShipviaCodeMapRepository.class),
                mock(ClientServiceCodeMapRepository.class),
                mock(ClientDestCountryMapRepository.class),
                mock(ClientPackageCodeMapRepository.class),
                mock(ShippingServiceRepository.class),
                mock(PackagePresetRepository.class),
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        assertThrows(AccessDeniedException.class,
                () -> service.list("OTHER", ClientCodeMapDTO.Kind.SHIPVIA));
        assertThrows(AccessDeniedException.class,
                () -> service.remove("OTHER", ClientCodeMapDTO.Kind.SHIPVIA, 1L));
    }

    // ===== Audit B1 + B5 — cross-tenant + not-found delete outcomes =====

    @Test
    void remove_missingIdReturns404() {
        // Platform ADMIN (no scope) — bypasses tenantScope guard.
        ClientShipviaCodeMapRepository shipviaRepo = mock(ClientShipviaCodeMapRepository.class);
        when(shipviaRepo.findById(99L)).thenReturn(Optional.empty());
        ClientCodeMapServiceImpl service = new ClientCodeMapServiceImpl(
                mock(ClientRepository.class), shipviaRepo,
                mock(ClientServiceCodeMapRepository.class),
                mock(ClientDestCountryMapRepository.class),
                mock(ClientPackageCodeMapRepository.class),
                mock(ShippingServiceRepository.class),
                mock(PackagePresetRepository.class),
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        ApiResponse<Void> resp = service.remove("ACME", ClientCodeMapDTO.Kind.SHIPVIA, 99L);

        assertEquals(404, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
        verify(shipviaRepo, never()).delete(any());
    }

    @Test
    void remove_crossTenantIdReturns400() {
        // Platform ADMIN pointing at wrong client path — pre-fix this
        // silently 200'd. Now surfaces the mismatch.
        ClientShipviaCodeMap other = new ClientShipviaCodeMap();
        other.setId(7L);
        other.setClientCode("OTHER");
        ClientShipviaCodeMapRepository shipviaRepo = mock(ClientShipviaCodeMapRepository.class);
        when(shipviaRepo.findById(7L)).thenReturn(Optional.of(other));
        ClientCodeMapServiceImpl service = new ClientCodeMapServiceImpl(
                mock(ClientRepository.class), shipviaRepo,
                mock(ClientServiceCodeMapRepository.class),
                mock(ClientDestCountryMapRepository.class),
                mock(ClientPackageCodeMapRepository.class),
                mock(ShippingServiceRepository.class),
                mock(PackagePresetRepository.class),
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        ApiResponse<Void> resp = service.remove("ACME", ClientCodeMapDTO.Kind.SHIPVIA, 7L);

        assertEquals(400, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
        verify(shipviaRepo, never()).delete(any());
    }

    @Test
    void remove_matchingClientDeletes() {
        ClientShipviaCodeMap own = new ClientShipviaCodeMap();
        own.setId(3L);
        own.setClientCode("ACME");
        ClientShipviaCodeMapRepository shipviaRepo = mock(ClientShipviaCodeMapRepository.class);
        when(shipviaRepo.findById(3L)).thenReturn(Optional.of(own));
        ClientCodeMapServiceImpl service = new ClientCodeMapServiceImpl(
                mock(ClientRepository.class), shipviaRepo,
                mock(ClientServiceCodeMapRepository.class),
                mock(ClientDestCountryMapRepository.class),
                mock(ClientPackageCodeMapRepository.class),
                mock(ShippingServiceRepository.class),
                mock(PackagePresetRepository.class),
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        ApiResponse<Void> resp = service.remove("ACME", ClientCodeMapDTO.Kind.SHIPVIA, 3L);

        assertEquals(200, resp.getCode());
        verify(shipviaRepo).delete(own);
    }
}
