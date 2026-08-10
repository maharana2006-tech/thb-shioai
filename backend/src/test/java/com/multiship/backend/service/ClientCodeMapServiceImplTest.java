package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.dto.ClientCodeMapDTO;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

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
}
