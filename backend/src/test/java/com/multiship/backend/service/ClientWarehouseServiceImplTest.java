package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientWarehouseRepository;
import com.multiship.backend.repository.WarehouseRepository;
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
 * every path-param clientCode: a scoped USER hitting a foreign
 * client's warehouse endpoints must get a clean 403 before the DB is
 * consulted.
 */
class ClientWarehouseServiceImplTest {

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void scopedUserCannotReachForeignTenantWarehouses() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = User.withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        token.setDetails(new JwtAuthenticationFilter.AuthDetails("ACME"));
        SecurityContextHolder.getContext().setAuthentication(token);

        ClientWarehouseServiceImpl service = new ClientWarehouseServiceImpl(
                mock(ClientWarehouseRepository.class),
                mock(WarehouseRepository.class),
                mock(ClientRepository.class),
                mock(AuditService.class),
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        assertThrows(AccessDeniedException.class, () -> service.listForClient("OTHER"));
        assertThrows(AccessDeniedException.class, () -> service.detach("OTHER", "WH1"));
    }
}
