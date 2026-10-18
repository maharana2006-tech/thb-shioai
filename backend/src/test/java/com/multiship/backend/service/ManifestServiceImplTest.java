package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.dto.ManifestRequestDTO;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.CarrierEodLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

        // FDX-G2 — constructor gained 4 classification-chain deps
        // (OrderTracking, Order, ClientShipviaCodeMap, ShippingService
        // repos). Test only exercises the tenant-clamp early-exit path so
        // the deps go in as mocks and never fire.
        ManifestServiceImpl service = new ManifestServiceImpl(
                mock(CarrierService.class),
                mock(CarrierAccountRefRepository.class),
                new TenantScopeEnforcer(new AccessScopePolicy(true)),
                mock(com.multiship.backend.repository.OrderTrackingRepository.class),
                mock(com.multiship.backend.repository.OrderRepository.class),
                mock(com.multiship.backend.repository.ClientShipviaCodeMapRepository.class),
                mock(com.multiship.backend.repository.ShippingServiceRepository.class),
                mock(com.multiship.backend.repository.CarrierEodLogRepository.class),
                mock(com.multiship.backend.service.CarrierEodLogger.class));

        ManifestRequestDTO req = new ManifestRequestDTO();
        req.setCarrierCode("UPS");
        req.setTrackingNumbers(List.of("1Z"));
        req.setCustomerNo("OTHER");

        assertThrows(AccessDeniedException.class, () -> service.closeOut(req));
    }

    /**
     * Cross-tenant EOD audit leak regression: a scoped USER's recentEvents()
     * must query only their own client's rows, never the unscoped findAll
     * (which returned every tenant's account numbers / customerNo / createdBy).
     */
    @Test
    void scopedUserOnlySeesOwnTenantEvents() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = User.withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        token.setDetails(new JwtAuthenticationFilter.AuthDetails("ACME"));
        SecurityContextHolder.getContext().setAuthentication(token);

        CarrierEodLogRepository eodRepo = mock(CarrierEodLogRepository.class);
        when(eodRepo.findByCustomerNoIgnoreCaseOrderByCreatedAtDesc(eq("ACME"), any()))
                .thenReturn(List.of());

        ManifestServiceImpl service = new ManifestServiceImpl(
                mock(CarrierService.class),
                mock(CarrierAccountRefRepository.class),
                new TenantScopeEnforcer(new AccessScopePolicy(true)),
                mock(com.multiship.backend.repository.OrderTrackingRepository.class),
                mock(com.multiship.backend.repository.OrderRepository.class),
                mock(com.multiship.backend.repository.ClientShipviaCodeMapRepository.class),
                mock(com.multiship.backend.repository.ShippingServiceRepository.class),
                eodRepo,
                mock(com.multiship.backend.service.CarrierEodLogger.class));

        service.recentEvents(50);

        verify(eodRepo).findByCustomerNoIgnoreCaseOrderByCreatedAtDesc(eq("ACME"), any());
        verify(eodRepo, never()).findAllByOrderByCreatedAtDesc(any(Pageable.class));
    }
}
