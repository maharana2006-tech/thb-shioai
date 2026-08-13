package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.dto.AllowPackageRequest;
import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.ClientAllowedPackageDTO;
import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.ClientAllowedPackage;
import com.multiship.backend.model.PackagePreset;
import com.multiship.backend.repository.ClientAllowedPackageRepository;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.PackagePresetRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 51 T5b (audit finding #11) — coverage for the allowed-package
 * gating service. Enforces which package presets a client may use.
 */
class ClientAllowedPackageServiceImplTest {

    private ClientAllowedPackageRepository repo;
    private ClientRepository clientRepo;
    private PackagePresetRepository presetRepo;
    private ClientAllowedPackageServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(ClientAllowedPackageRepository.class);
        clientRepo = mock(ClientRepository.class);
        presetRepo = mock(PackagePresetRepository.class);
        service = new ClientAllowedPackageServiceImpl(repo, clientRepo, presetRepo);
        ReflectionTestUtils.setField(service, "tenantScope",
                new TenantScopeEnforcer(new AccessScopePolicy(false)));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void allowSavesNewLinkAsDefaultWhenFirst() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        PackagePreset preset = new PackagePreset();
        preset.setId(9L);
        preset.setName("Small Box");
        when(presetRepo.findById(9L)).thenReturn(Optional.of(preset));
        when(repo.existsByClientCodeIgnoreCaseAndPresetId("ACME", 9L)).thenReturn(false);
        when(repo.findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of());

        AllowPackageRequest req = new AllowPackageRequest();
        req.setPresetId(9L);
        ApiResponse<ClientAllowedPackageDTO> resp = service.allow("acme", req);

        assertEquals(200, resp.getCode());
        verify(repo).save(any(ClientAllowedPackage.class));
    }

    @Test
    void allowReturns404WhenClientMissing() {
        when(clientRepo.existsByClientCodeIgnoreCase(anyString())).thenReturn(false);
        AllowPackageRequest req = new AllowPackageRequest();
        req.setPresetId(9L);
        ApiResponse<ClientAllowedPackageDTO> resp = service.allow("GHOST", req);
        assertEquals(404, resp.getCode());
        assertEquals(ErrorCode.CLIENT_NOT_FOUND.name(), resp.getErrorCode());
    }

    @Test
    void scopedUserCannotReachForeignClientPackageAllowlist() {
        putScopedUser();
        ReflectionTestUtils.setField(service, "tenantScope",
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        assertThrows(AccessDeniedException.class, () -> service.listForClient("OTHER"));
        assertThrows(AccessDeniedException.class, () -> service.remove("OTHER", 9L));
        verify(clientRepo, never()).existsByClientCodeIgnoreCase(anyString());
    }

    @Test
    void allowReturns409WhenPresetAlreadyOnList() {
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        PackagePreset preset = new PackagePreset();
        preset.setId(9L);
        preset.setName("Small Box");
        when(presetRepo.findById(9L)).thenReturn(Optional.of(preset));
        when(repo.existsByClientCodeIgnoreCaseAndPresetId("ACME", 9L)).thenReturn(true);

        AllowPackageRequest req = new AllowPackageRequest();
        req.setPresetId(9L);
        ApiResponse<ClientAllowedPackageDTO> resp = service.allow("ACME", req);
        assertEquals(409, resp.getCode());
        assertEquals(ErrorCode.ALLOWLIST_ALREADY_EXISTS.name(), resp.getErrorCode());
        verify(repo, never()).save(any(ClientAllowedPackage.class));
    }

    private void putScopedUser() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = User.withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        token.setDetails(new JwtAuthenticationFilter.AuthDetails("ACME"));
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
