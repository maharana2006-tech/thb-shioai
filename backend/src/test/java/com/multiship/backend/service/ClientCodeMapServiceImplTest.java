package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientCodeMapDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.dto.UpsertClientCodeMapRequest;
import com.multiship.backend.model.ClientShipviaCodeMap;
import com.multiship.backend.repository.ClientDestCountryMapRepository;
import com.multiship.backend.repository.ClientPackageCodeMapRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientServiceCodeMapRepository;
import com.multiship.backend.repository.ClientShipviaCodeMapPackageRepository;
import com.multiship.backend.repository.ClientShipviaCodeMapRepository;
import com.multiship.backend.repository.PackagePresetRepository;
import com.multiship.backend.repository.ShippingServiceRepository;
import com.multiship.backend.repository.WarehouseRepository;
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
                mock(WarehouseRepository.class),
                mock(ClientShipviaCodeMapPackageRepository.class),
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
                mock(WarehouseRepository.class),
                mock(ClientShipviaCodeMapPackageRepository.class),
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
                mock(WarehouseRepository.class),
                mock(ClientShipviaCodeMapPackageRepository.class),
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        ApiResponse<Void> resp = service.remove("ACME", ClientCodeMapDTO.Kind.SHIPVIA, 7L);

        assertEquals(400, resp.getCode());
        assertEquals(ErrorCode.VALIDATION_ERROR.name(), resp.getErrorCode());
        verify(shipviaRepo, never()).delete(any());
    }

    // ===== Audit B3 (#372) — erp casing normalisation on upsert =====

    @Test
    void upsert_normalisesErpCodeToUppercaseBeforeSave() {
        // Pre-fix: a save of "p80" stored the row with lowercase, then a
        // save of "P80" matched case-insensitively but inherited the
        // original casing. Case-sensitive audit queries then missed rows.
        // Post-fix: both saves land at "P80".
        ClientRepository clientRepo = mock(ClientRepository.class);
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);

        ShippingServiceRepository serviceRepo = mock(ShippingServiceRepository.class);
        // Target id must resolve or upsertShipvia short-circuits with 400.
        when(serviceRepo.findById(99L)).thenReturn(Optional.of(
                new com.multiship.backend.model.ShippingService()));

        ClientShipviaCodeMapRepository shipviaRepo = mock(ClientShipviaCodeMapRepository.class);
        // Audit B2 (#371) — upsert now uses the targeted finder, so the
        // stub swap goes here. Empty = no existing row, insert path runs.
        when(shipviaRepo.findForUpsert(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(shipviaRepo.save(any())).thenAnswer(inv -> {
            ClientShipviaCodeMap r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });

        ClientCodeMapServiceImpl service = new ClientCodeMapServiceImpl(
                clientRepo, shipviaRepo,
                mock(ClientServiceCodeMapRepository.class),
                mock(ClientDestCountryMapRepository.class),
                mock(ClientPackageCodeMapRepository.class),
                serviceRepo,
                mock(PackagePresetRepository.class),
                mock(WarehouseRepository.class),
                mock(ClientShipviaCodeMapPackageRepository.class),
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        UpsertClientCodeMapRequest req = new UpsertClientCodeMapRequest();
        req.setErpCode("  p80  ");
        req.setTargetId(99L);
        service.upsert("ACME", ClientCodeMapDTO.Kind.SHIPVIA, req);

        var captor = org.mockito.ArgumentCaptor.forClass(ClientShipviaCodeMap.class);
        verify(shipviaRepo).save(captor.capture());
        assertEquals("P80", captor.getValue().getErpCode(),
                "erpCode must be trimmed + uppercased before save");
        // Audit B2 (#371) — upsert must NOT pull the whole client list.
        verify(shipviaRepo, never()).findByClientCodeIgnoreCaseOrderByErpCodeAsc(any());
        verify(shipviaRepo).findForUpsert(any(), any(), any(), any());
    }

    @Test
    void upsert_reusesExistingRowReturnedByTargetedFinder() {
        // Audit B2 (#371) — when the targeted finder returns the matching
        // row, upsert updates it in place (does NOT insert a new row).
        ClientRepository clientRepo = mock(ClientRepository.class);
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);

        ShippingServiceRepository serviceRepo = mock(ShippingServiceRepository.class);
        when(serviceRepo.findById(99L)).thenReturn(Optional.of(
                new com.multiship.backend.model.ShippingService()));

        ClientShipviaCodeMap existing = ClientShipviaCodeMap.builder()
                .clientCode("ACME").erpCode("P80").serviceId(1L).build();
        existing.setId(55L);
        ClientShipviaCodeMapRepository shipviaRepo = mock(ClientShipviaCodeMapRepository.class);
        when(shipviaRepo.findForUpsert("ACME", "P80", null, null)).thenReturn(Optional.of(existing));
        when(shipviaRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClientCodeMapServiceImpl service = new ClientCodeMapServiceImpl(
                clientRepo, shipviaRepo,
                mock(ClientServiceCodeMapRepository.class),
                mock(ClientDestCountryMapRepository.class),
                mock(ClientPackageCodeMapRepository.class),
                serviceRepo,
                mock(PackagePresetRepository.class),
                mock(WarehouseRepository.class),
                mock(ClientShipviaCodeMapPackageRepository.class),
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        UpsertClientCodeMapRequest req = new UpsertClientCodeMapRequest();
        req.setErpCode("P80");
        req.setTargetId(99L);
        service.upsert("ACME", ClientCodeMapDTO.Kind.SHIPVIA, req);

        var captor = org.mockito.ArgumentCaptor.forClass(ClientShipviaCodeMap.class);
        verify(shipviaRepo).save(captor.capture());
        // Same row id = update, not insert.
        assertEquals(55L, captor.getValue().getId());
        assertEquals(99L, captor.getValue().getServiceId(),
                "serviceId must be updated to the new target");
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
                mock(WarehouseRepository.class),
                mock(ClientShipviaCodeMapPackageRepository.class),
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        ApiResponse<Void> resp = service.remove("ACME", ClientCodeMapDTO.Kind.SHIPVIA, 3L);

        assertEquals(200, resp.getCode());
        verify(shipviaRepo).delete(own);
    }
}
