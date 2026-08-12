package com.multiship.backend.service;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.WarehouseDTO;
import com.multiship.backend.dto.WarehouseUpsertRequest;
import com.multiship.backend.model.Warehouse;
import com.multiship.backend.repository.ClientRepository;
import com.multiship.backend.repository.ClientWarehouseRepository;
import com.multiship.backend.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 50 Tier 0.5 PR H — tenant-scope guard tests for
 * {@link WarehouseServiceImpl}. Two dimensions:
 * <ul>
 *   <li>PLATFORM-owned rows skip the clamp entirely (the org-wide
 *       catalog stays readable/writable by operators).</li>
 *   <li>CLIENT-owned rows go through {@code clampClientCode} on
 *       write and {@code requireTenantMatch} on row-level ops.</li>
 * </ul>
 */
class WarehouseServiceImplTest {

    private WarehouseRepository warehouseRepo;
    private ClientWarehouseRepository clientWarehouseRepo;
    private ClientRepository clientRepo;
    private AuditService auditService;
    private TenantScopeEnforcer tenantScope;
    private WarehouseServiceImpl service;

    @BeforeEach
    void setUp() {
        warehouseRepo = mock(WarehouseRepository.class);
        clientWarehouseRepo = mock(ClientWarehouseRepository.class);
        clientRepo = mock(ClientRepository.class);
        auditService = mock(AuditService.class);
        tenantScope = mock(TenantScopeEnforcer.class);

        service = new WarehouseServiceImpl(
                warehouseRepo, clientWarehouseRepo, clientRepo, auditService);
        ReflectionTestUtils.setField(service, "tenantScope", tenantScope);
    }

    private Warehouse clientWarehouse(String code, String ownerClientCode) {
        return Warehouse.builder()
                .id(42L)
                .code(code)
                .name("Warehouse " + code)
                .ownerType(Warehouse.OWNER_CLIENT)
                .ownerClientCode(ownerClientCode)
                .active(true)
                .build();
    }

    private Warehouse platformWarehouse(String code) {
        return Warehouse.builder()
                .id(1L)
                .code(code)
                .name("Platform " + code)
                .ownerType(Warehouse.OWNER_PLATFORM)
                .ownerClientCode(null)
                .active(true)
                .build();
    }

    private WarehouseUpsertRequest req(String code, String ownerType, String ownerClientCode) {
        return WarehouseUpsertRequest.builder()
                .code(code)
                .name("Name " + code)
                .ownerType(ownerType)
                .ownerClientCode(ownerClientCode)
                .active(true)
                .build();
    }

    /* -------------------------- getWarehouse -------------------------- */

    @Test
    void getWarehouse_clientOwnedForeignTenant_isDenied() {
        when(warehouseRepo.findByCodeIgnoreCase("WH1"))
                .thenReturn(Optional.of(clientWarehouse("WH1", "OTHER")));
        doThrow(new AccessDeniedException("cross tenant"))
                .when(tenantScope).requireTenantMatch("OTHER");

        assertThrows(AccessDeniedException.class, () -> service.getWarehouse("WH1"));
    }

    @Test
    void getWarehouse_platformOwned_skipsTenantGuard() {
        when(warehouseRepo.findByCodeIgnoreCase("PWH"))
                .thenReturn(Optional.of(platformWarehouse("PWH")));

        ApiResponse<WarehouseDTO> resp = service.getWarehouse("PWH");

        assertEquals("SUCCESS", resp.getStatus());
        verify(tenantScope, never()).requireTenantMatch(anyString());
    }

    /* -------------------------- createWarehouse -------------------------- */

    @Test
    void createWarehouse_clientOwnedForeignCode_isRejectedAtClamp() {
        when(warehouseRepo.existsByCodeIgnoreCase("WH1")).thenReturn(false);
        when(tenantScope.clampClientCode("OTHER"))
                .thenThrow(new AccessDeniedException("cross tenant"));

        assertThrows(AccessDeniedException.class,
                () -> service.createWarehouse(req("WH1", "CLIENT", "OTHER")));
        verify(warehouseRepo, never()).save(any());
    }

    @Test
    void createWarehouse_platformOwned_skipsClamp() {
        when(warehouseRepo.existsByCodeIgnoreCase("PWH")).thenReturn(false);
        when(warehouseRepo.save(any(Warehouse.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<WarehouseDTO> resp = service.createWarehouse(
                req("PWH", "PLATFORM", null));

        assertEquals("SUCCESS", resp.getStatus());
        verify(tenantScope, never()).clampClientCode(anyString());
        verify(warehouseRepo).save(any(Warehouse.class));
    }

    @Test
    void createWarehouse_clientOwnedOwnTenant_clampReturnsOwnCode() {
        when(warehouseRepo.existsByCodeIgnoreCase("WH1")).thenReturn(false);
        when(tenantScope.clampClientCode("ACME")).thenReturn("ACME");
        when(clientRepo.existsByClientCodeIgnoreCase("ACME")).thenReturn(true);
        when(warehouseRepo.save(any(Warehouse.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<WarehouseDTO> resp = service.createWarehouse(
                req("WH1", "CLIENT", "ACME"));

        assertEquals("SUCCESS", resp.getStatus());
        ArgumentCaptor<Warehouse> captor = ArgumentCaptor.forClass(Warehouse.class);
        verify(warehouseRepo).save(captor.capture());
        assertEquals("ACME", captor.getValue().getOwnerClientCode());
    }

    /* -------------------------- updateWarehouse -------------------------- */

    @Test
    void updateWarehouse_clientOwnedForeignTenant_isDenied() {
        when(warehouseRepo.findByCodeIgnoreCase("WH1"))
                .thenReturn(Optional.of(clientWarehouse("WH1", "OTHER")));
        doThrow(new AccessDeniedException("cross tenant"))
                .when(tenantScope).requireTenantMatch("OTHER");

        assertThrows(AccessDeniedException.class,
                () -> service.updateWarehouse("WH1", req("WH1", "CLIENT", "OTHER")));
        verify(warehouseRepo, never()).save(any());
    }

    @Test
    void updateWarehouse_platformOwned_skipsClampAndRequireMatch() {
        when(warehouseRepo.findByCodeIgnoreCase("PWH"))
                .thenReturn(Optional.of(platformWarehouse("PWH")));
        when(warehouseRepo.save(any(Warehouse.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<WarehouseDTO> resp = service.updateWarehouse(
                "PWH", req("PWH", "PLATFORM", null));

        assertEquals("SUCCESS", resp.getStatus());
        verify(tenantScope, never()).requireTenantMatch(anyString());
        verify(tenantScope, never()).clampClientCode(anyString());
    }

    /* -------------------------- toggleActive -------------------------- */

    @Test
    void toggleActive_clientOwnedForeignTenant_isDenied() {
        when(warehouseRepo.findByCodeIgnoreCase("WH1"))
                .thenReturn(Optional.of(clientWarehouse("WH1", "OTHER")));
        doThrow(new AccessDeniedException("cross tenant"))
                .when(tenantScope).requireTenantMatch("OTHER");

        assertThrows(AccessDeniedException.class, () -> service.toggleActive("WH1"));
        verify(warehouseRepo, never()).save(any());
    }

    @Test
    void toggleActive_platformOwned_skipsTenantGuard() {
        when(warehouseRepo.findByCodeIgnoreCase("PWH"))
                .thenReturn(Optional.of(platformWarehouse("PWH")));
        when(warehouseRepo.save(any(Warehouse.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<WarehouseDTO> resp = service.toggleActive("PWH");

        assertEquals("SUCCESS", resp.getStatus());
        verify(tenantScope, never()).requireTenantMatch(anyString());
    }

    /* -------------------------- deleteWarehouse -------------------------- */

    @Test
    void deleteWarehouse_clientOwnedForeignTenant_isDenied() {
        when(warehouseRepo.findByCodeIgnoreCase("WH1"))
                .thenReturn(Optional.of(clientWarehouse("WH1", "OTHER")));
        doThrow(new AccessDeniedException("cross tenant"))
                .when(tenantScope).requireTenantMatch("OTHER");

        assertThrows(AccessDeniedException.class, () -> service.deleteWarehouse("WH1"));
        verify(warehouseRepo, never()).delete(any());
    }

    @Test
    void deleteWarehouse_platformOwned_skipsTenantGuardAndDeletes() {
        Warehouse platform = platformWarehouse("PWH");
        when(warehouseRepo.findByCodeIgnoreCase("PWH"))
                .thenReturn(Optional.of(platform));
        when(clientWarehouseRepo.countByWarehouseId(platform.getId())).thenReturn(0L);

        ApiResponse<Void> resp = service.deleteWarehouse("PWH");

        assertEquals("SUCCESS", resp.getStatus());
        verify(tenantScope, never()).requireTenantMatch(anyString());
        verify(warehouseRepo).delete(platform);
    }
}
