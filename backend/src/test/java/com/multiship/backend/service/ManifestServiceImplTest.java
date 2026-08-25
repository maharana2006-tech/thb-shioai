package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.dto.ManifestRequestDTO;
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
 * ManifestServiceImpl request body. A scoped USER hitting closeOut with
 * a foreign customerNo must land in AccessDeniedException before the
 * carrier is contacted.
 */
class ManifestServiceImplTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void scopedUserCannotCloseOutForeignTenant() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = User.withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        token.setDetails(new JwtAuthenticationFilter.AuthDetails("ACME"));
        SecurityContextHolder.getContext().setAuthentication(token);

        // FDX-G2 — constructor gained 5 classification-chain deps
        // (OrderTracking, Order, ClientShipviaCodeMap, ShipViaMapping,
        // ShippingService repos). Test only exercises the tenant-clamp
        // early-exit path so the deps go in as mocks and never fire.
        ManifestServiceImpl service = new ManifestServiceImpl(
                mock(CarrierService.class),
                mock(CarrierAccountRefRepository.class),
                new TenantScopeEnforcer(new AccessScopePolicy(true)),
                mock(com.multiship.backend.repository.OrderTrackingRepository.class),
                mock(com.multiship.backend.repository.OrderRepository.class),
                mock(com.multiship.backend.repository.ClientShipviaCodeMapRepository.class),
                mock(com.multiship.backend.repository.ShipViaMappingRepository.class),
                mock(com.multiship.backend.repository.ShippingServiceRepository.class));

        ManifestRequestDTO req = new ManifestRequestDTO();
        req.setCarrierCode("UPS");
        req.setTrackingNumbers(List.of("1Z"));
        req.setCustomerNo("OTHER");

        assertThrows(AccessDeniedException.class, () -> service.closeOut(req));
    }
}
