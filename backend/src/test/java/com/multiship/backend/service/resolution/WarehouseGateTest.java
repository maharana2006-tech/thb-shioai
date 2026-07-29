package com.multiship.backend.service.resolution;

import com.multiship.backend.dto.ErrorCode;
import com.multiship.backend.model.ClientAllowedService;
import com.multiship.backend.model.ClientAllowedServiceWarehouse;
import com.multiship.backend.repository.ClientAllowedPackageRepository;
import com.multiship.backend.repository.ClientAllowedServiceDestinationRepository;
import com.multiship.backend.repository.ClientAllowedServiceRepository;
import com.multiship.backend.repository.ClientAllowedServiceWarehouseRepository;
import com.multiship.backend.repository.ClientBillingMarkupRepository;
import com.multiship.backend.repository.ClientDestinationRuleRepository;
import com.multiship.backend.repository.ClientShippingPolicyRepository;
import com.multiship.backend.repository.ClientWarehouseRepository;
import com.multiship.backend.repository.PackagePresetRepository;
import com.multiship.backend.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G1 — warehouse-gate on the client service allowlist. Empty gate = any
 * warehouse OK (backward compat); populated gate = only listed warehouses.
 */
class WarehouseGateTest {

    private ClientAllowedServiceRepository allowedServiceRepository;
    private ClientAllowedServiceWarehouseRepository warehouseGateRepository;
    private ClientAllowedServiceDestinationRepository destinationGateRepository;
    private ShipmentResolutionServiceImpl service;

    @BeforeEach
    void setUp() {
        allowedServiceRepository = mock(ClientAllowedServiceRepository.class);
        warehouseGateRepository = mock(ClientAllowedServiceWarehouseRepository.class);
        destinationGateRepository = mock(ClientAllowedServiceDestinationRepository.class);
        service = new ShipmentResolutionServiceImpl(
                mock(WarehouseRepository.class),
                mock(ClientWarehouseRepository.class),
                allowedServiceRepository,
                destinationGateRepository,
                warehouseGateRepository,
                mock(ClientAllowedPackageRepository.class),
                mock(ClientDestinationRuleRepository.class),
                mock(ClientShippingPolicyRepository.class),
                mock(ClientBillingMarkupRepository.class),
                mock(PackagePresetRepository.class));
    }

    // ===== allowedServiceIds(clientCode, warehouseId) =====

    @Test
    void allowedServiceIdsNullWarehouseReturnsAll() {
        // Two allow rows, ignore any gate state — null warehouse skips gate.
        when(allowedServiceRepository.findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of(allow(1L, 10L), allow(2L, 20L)));
        Set<Long> result = service.allowedServiceIds("ACME", null);
        assertEquals(Set.of(10L, 20L), result);
    }

    @Test
    void allowedServiceIdsUnrestrictedGateIncludesService() {
        // Service 10 has NO warehouse gate rows → allowed at any warehouse.
        when(allowedServiceRepository.findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of(allow(1L, 10L)));
        when(warehouseGateRepository.findByAllowedServiceIdIn(List.of(1L)))
                .thenReturn(List.of()); // empty gate
        Set<Long> result = service.allowedServiceIds("ACME", 99L);
        assertEquals(Set.of(10L), result);
    }

    @Test
    void allowedServiceIdsRestrictedGateFiltersOut() {
        // Service 10 gated to warehouse 42 only; asking about warehouse 99.
        when(allowedServiceRepository.findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of(allow(1L, 10L)));
        when(warehouseGateRepository.findByAllowedServiceIdIn(List.of(1L)))
                .thenReturn(List.of(gate(1L, 42L)));
        Set<Long> result = service.allowedServiceIds("ACME", 99L);
        assertTrue(result.isEmpty(), "warehouse 99 not on gate → service should be filtered out");
    }

    @Test
    void allowedServiceIdsRestrictedGateIncludesListedWarehouse() {
        when(allowedServiceRepository.findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of(allow(1L, 10L)));
        when(warehouseGateRepository.findByAllowedServiceIdIn(List.of(1L)))
                .thenReturn(List.of(gate(1L, 42L)));
        Set<Long> result = service.allowedServiceIds("ACME", 42L);
        assertEquals(Set.of(10L), result);
    }

    @Test
    void allowedServiceIdsMixedGates() {
        // Service 10: gated to [42]. Service 20: no gate.
        when(allowedServiceRepository.findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of(allow(1L, 10L), allow(2L, 20L)));
        when(warehouseGateRepository.findByAllowedServiceIdIn(List.of(1L, 2L)))
                .thenReturn(List.of(gate(1L, 42L)));
        // Asking about warehouse 99: service 20 (no gate) passes, service 10 (gated to 42) fails.
        assertEquals(Set.of(20L), service.allowedServiceIds("ACME", 99L));
        // Asking about warehouse 42: both pass.
        assertEquals(Set.of(10L, 20L), service.allowedServiceIds("ACME", 42L));
    }

    // ===== assertServiceAllowed(clientCode, serviceId, destCountry, warehouseId) =====

    @Test
    void assertServiceAllowedNullWarehouseSkipsGate() {
        when(allowedServiceRepository.findByClientCodeIgnoreCaseAndServiceId("ACME", 10L))
                .thenReturn(java.util.Optional.of(allow(1L, 10L)));
        when(destinationGateRepository.findByAllowedServiceIdOrderByCountryAsc(1L))
                .thenReturn(List.of());
        // No stubbing on warehouseGateRepository — code path should not hit it.
        assertDoesNotThrow(() -> service.assertServiceAllowed("ACME", 10L, "US", null));
    }

    @Test
    void assertServiceAllowedUnrestrictedWarehouseGatePasses() {
        when(allowedServiceRepository.findByClientCodeIgnoreCaseAndServiceId("ACME", 10L))
                .thenReturn(java.util.Optional.of(allow(1L, 10L)));
        when(destinationGateRepository.findByAllowedServiceIdOrderByCountryAsc(1L))
                .thenReturn(List.of());
        when(warehouseGateRepository.findByAllowedServiceIdOrderByWarehouseIdAsc(1L))
                .thenReturn(List.of()); // empty → any warehouse allowed
        assertDoesNotThrow(() -> service.assertServiceAllowed("ACME", 10L, "US", 99L));
    }

    @Test
    void assertServiceAllowedRestrictedWarehouseGateBlocks() {
        when(allowedServiceRepository.findByClientCodeIgnoreCaseAndServiceId("ACME", 10L))
                .thenReturn(java.util.Optional.of(allow(1L, 10L)));
        when(destinationGateRepository.findByAllowedServiceIdOrderByCountryAsc(1L))
                .thenReturn(List.of());
        when(warehouseGateRepository.findByAllowedServiceIdOrderByWarehouseIdAsc(1L))
                .thenReturn(List.of(gate(1L, 42L)));
        ShipmentResolutionException ex = assertThrows(ShipmentResolutionException.class,
                () -> service.assertServiceAllowed("ACME", 10L, "US", 99L));
        assertEquals(ErrorCode.SERVICE_NOT_ALLOWED_FOR_WAREHOUSE, ex.getErrorCode());
    }

    @Test
    void assertServiceAllowedRestrictedWarehouseGateAllowsListed() {
        when(allowedServiceRepository.findByClientCodeIgnoreCaseAndServiceId("ACME", 10L))
                .thenReturn(java.util.Optional.of(allow(1L, 10L)));
        when(destinationGateRepository.findByAllowedServiceIdOrderByCountryAsc(1L))
                .thenReturn(List.of());
        when(warehouseGateRepository.findByAllowedServiceIdOrderByWarehouseIdAsc(1L))
                .thenReturn(List.of(gate(1L, 42L)));
        assertDoesNotThrow(() -> service.assertServiceAllowed("ACME", 10L, "US", 42L));
    }

    @Test
    void threeArgOverloadStillWorks() {
        // Existing callers using the 3-arg signature must not hit the warehouse gate.
        when(allowedServiceRepository.findByClientCodeIgnoreCaseAndServiceId("ACME", 10L))
                .thenReturn(java.util.Optional.of(allow(1L, 10L)));
        when(destinationGateRepository.findByAllowedServiceIdOrderByCountryAsc(1L))
                .thenReturn(List.of());
        assertDoesNotThrow(() -> service.assertServiceAllowed("ACME", 10L, "US"));
    }

    // ===== helpers =====

    private static ClientAllowedService allow(Long id, Long serviceId) {
        return ClientAllowedService.builder()
                .id(id).clientCode("ACME").serviceId(serviceId).isDefault(false).build();
    }

    private static ClientAllowedServiceWarehouse gate(Long allowedServiceId, Long warehouseId) {
        return ClientAllowedServiceWarehouse.builder()
                .allowedServiceId(allowedServiceId)
                .clientCode("ACME")
                .warehouseId(warehouseId)
                .build();
    }
}
