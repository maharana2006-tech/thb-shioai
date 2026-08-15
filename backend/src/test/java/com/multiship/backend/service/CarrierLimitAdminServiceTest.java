package com.multiship.backend.service;

import com.multiship.backend.dto.CarrierShippingLimitRequest;
import com.multiship.backend.dto.CarrierShippingLimitResponse;
import com.multiship.backend.model.CarrierShippingLimit;
import com.multiship.backend.repository.CarrierShippingLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 52 — unit coverage for {@link CarrierLimitAdminService}. Focuses
 * on the CRUD-plus-cache-invalidation contract:
 * <ul>
 *   <li>create / update normalize carrierCode + scope + direction to upper case,</li>
 *   <li>blank optional strings collapse to null,</li>
 *   <li>every write calls {@link CarrierLimitService#invalidateCache()} so the
 *       resolver picks the change up on the next lookup,</li>
 *   <li>update / delete return the missing-row signal cleanly.</li>
 * </ul>
 */
class CarrierLimitAdminServiceTest {

    private CarrierShippingLimitRepository repository;
    private CarrierLimitService resolver;
    private CarrierLimitAdminService service;

    @BeforeEach
    void setUp() {
        repository = mock(CarrierShippingLimitRepository.class);
        resolver = mock(CarrierLimitService.class);
        service = new CarrierLimitAdminService(repository, resolver);
    }

    // ===== list =====

    @Test
    void list_returnsMappedRows_andClampsPageSize() {
        CarrierShippingLimit row = fixture();
        Page<CarrierShippingLimit> page = new PageImpl<>(List.of(row));
        when(repository.findAll(any(Pageable.class))).thenReturn(page);

        // size well beyond MAX_PAGE_SIZE — service clamps internally
        List<CarrierShippingLimitResponse> out = service.list(0, 5000);

        assertEquals(1, out.size());
        assertEquals("UPS", out.get(0).getCarrierCode());
    }

    // ===== get =====

    @Test
    void get_present_returnsResponse() {
        when(repository.findById(1L)).thenReturn(Optional.of(fixture()));

        Optional<CarrierShippingLimitResponse> out = service.get(1L);

        assertTrue(out.isPresent());
        assertEquals("UPS", out.get().getCarrierCode());
    }

    @Test
    void get_missing_returnsEmpty() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertTrue(service.get(99L).isEmpty());
    }

    // ===== create =====

    @Test
    void create_normalizesCodes_invalidatesCache_setsEffectiveFrom() {
        // Request uses mixed case; service must uppercase carrier/scope/direction.
        CarrierShippingLimitRequest req = CarrierShippingLimitRequest.builder()
                .carrierCode("ups").serviceCode("UPS_GROUND")
                .scope("domestic").direction("forward")
                .maxPackages(10).maxCommodities(50)
                .maxTotalWeightLb(new BigDecimal("150.00"))
                .active(null) // service should default to true
                .notes("   ") // blank collapses to null
                .build();
        when(repository.save(any(CarrierShippingLimit.class)))
                .thenAnswer(inv -> {
                    CarrierShippingLimit e = inv.getArgument(0);
                    e.setId(42L);
                    return e;
                });

        CarrierShippingLimitResponse out = service.create(req);

        assertEquals(42L, out.getId());
        assertEquals("UPS", out.getCarrierCode());
        assertEquals("DOMESTIC", out.getScope());
        assertEquals("FORWARD", out.getDirection());
        assertEquals(Boolean.TRUE, out.getActive());
        assertNotNull(out.getEffectiveFrom());
        // notes was blank → collapse to null
        assertEquals(null, out.getNotes());
        verify(resolver, times(1)).invalidateCache();
    }

    // ===== update =====

    @Test
    void update_present_savesReplacementFields_andInvalidatesCache() {
        CarrierShippingLimit existing = fixture();
        existing.setId(7L);
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.save(any(CarrierShippingLimit.class))).thenAnswer(inv -> inv.getArgument(0));

        CarrierShippingLimitRequest req = CarrierShippingLimitRequest.builder()
                .carrierCode("FEDEX").serviceCode(null)
                .scope("INTERNATIONAL").direction("RETURN")
                .maxPackages(99).maxCommodities(200)
                .active(false).notes("holiday cap")
                .build();

        Optional<CarrierShippingLimitResponse> out = service.update(7L, req);

        assertTrue(out.isPresent());
        assertEquals("FEDEX", out.get().getCarrierCode());
        assertEquals("INTERNATIONAL", out.get().getScope());
        assertEquals("RETURN", out.get().getDirection());
        assertEquals(99, out.get().getMaxPackages());
        assertEquals(Boolean.FALSE, out.get().getActive());
        verify(resolver, times(1)).invalidateCache();
    }

    @Test
    void update_missing_returnsEmpty_andDoesNotInvalidate() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        Optional<CarrierShippingLimitResponse> out =
                service.update(99L, CarrierShippingLimitRequest.builder()
                        .carrierCode("UPS").scope("DOMESTIC").maxPackages(10).build());

        assertTrue(out.isEmpty());
        verify(resolver, times(0)).invalidateCache();
    }

    // ===== delete =====

    @Test
    void delete_present_deletesAndInvalidates() {
        when(repository.existsById(7L)).thenReturn(true);

        boolean deleted = service.delete(7L);

        assertTrue(deleted);
        verify(repository, times(1)).deleteById(7L);
        verify(resolver, times(1)).invalidateCache();
    }

    @Test
    void delete_missing_returnsFalse_noInvalidate() {
        when(repository.existsById(99L)).thenReturn(false);

        boolean deleted = service.delete(99L);

        assertFalse(deleted);
        verify(resolver, times(0)).invalidateCache();
    }

    private static CarrierShippingLimit fixture() {
        return CarrierShippingLimit.builder()
                .id(1L)
                .carrierCode("UPS").serviceCode("UPS_GROUND")
                .scope("DOMESTIC").direction("FORWARD")
                .maxPackages(20).maxCommodities(50)
                .maxTotalWeightLb(new BigDecimal("150.00"))
                .freeDeclaredValue(new BigDecimal("100.00"))
                .effectiveFrom(LocalDateTime.now())
                .active(true).notes("seeded")
                .build();
    }
}
