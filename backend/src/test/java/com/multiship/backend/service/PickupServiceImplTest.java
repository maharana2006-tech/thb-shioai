package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.dto.PickupRequestDTO;
import com.multiship.backend.repository.CarrierAccountRefRepository;
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
 * Sprint 50 Tier 0.5 PR E — locks in the tenant-scope clamp on the
 * PickupServiceImpl request body. A scoped USER scheduling a pickup for
 * a foreign customerNo must land in AccessDeniedException before the
 * carrier is contacted.
 */
class PickupServiceImplTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void scopedUserCannotSchedulePickupForForeignTenant() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = User.withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        token.setDetails(new JwtAuthenticationFilter.AuthDetails("ACME"));
        SecurityContextHolder.getContext().setAuthentication(token);

        PickupServiceImpl service = new PickupServiceImpl(
                mock(CarrierService.class),
                mock(CarrierAccountRefRepository.class),
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        PickupRequestDTO req = new PickupRequestDTO();
        req.setCarrierCode("UPS");
        req.setCustomerNo("OTHER");

        assertThrows(AccessDeniedException.class, () -> service.schedule(req));
    }
}
