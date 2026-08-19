package com.multiship.backend.service.warehouse;

import com.multiship.backend.dto.WarehouseSelectionResult;
import com.multiship.backend.model.Address;
import com.multiship.backend.model.ClientWarehouse;
import com.multiship.backend.model.Warehouse;
import com.multiship.backend.repository.ClientWarehouseRepository;
import com.multiship.backend.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G3 — warehouse-distance selector. Verifies the ordering rules:
 *   same country > postal-prefix within country > any attached (fallback).
 */
class WarehouseSelectorImplTest {

    private ClientWarehouseRepository clientWarehouseRepository;
    private WarehouseRepository warehouseRepository;
    private WarehouseSelectorImpl selector;

    @BeforeEach
    void setUp() {
        clientWarehouseRepository = mock(ClientWarehouseRepository.class);
        warehouseRepository = mock(WarehouseRepository.class);
        selector = new WarehouseSelectorImpl(clientWarehouseRepository, warehouseRepository);
    }

    // ===== NONE =====

    @Test
    void noAttachedWarehouses_matchReasonNone() {
        when(clientWarehouseRepository.findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of());
        WarehouseSelectionResult r = selector.selectNearest("ACME", "US", "10001");
        assertEquals("NONE", r.getMatchReason());
        assertNull(r.getSelectedWarehouseId());
        assertTrue(r.getCandidates().isEmpty());
    }

    // ===== COUNTRY_AND_POSTAL =====

    @Test
    void picksLongerPostalPrefixInSameCountry() {
        // Two US warehouses: 10001 and 90001. Destination 10005 shares 4
        // chars with 10001, 1 char with 90001.
        stubTwoAttached(
                warehouse(1L, "EAST", "East DC", "US", "10001"),
                warehouse(2L, "WEST", "West DC", "US", "90001"));
        WarehouseSelectionResult r = selector.selectNearest("ACME", "US", "10005");
        assertEquals("COUNTRY_AND_POSTAL", r.getMatchReason());
        assertEquals(1L, r.getSelectedWarehouseId());
        assertEquals(4, r.getPostalPrefixLength(),
                "10001 and 10005 share 4 leading chars");
        // Candidates carry per-row detail.
        assertEquals(2, r.getCandidates().size());
        assertTrue(r.getCandidates().get(0).getScore() > r.getCandidates().get(1).getScore());
    }

    @Test
    void ignoresPostalWhitespaceAndHyphen() {
        // UK-style postals normalize on both sides.
        stubTwoAttached(
                warehouse(1L, "LDN", "London DC", "GB", "SW1A 1AA"),
                warehouse(2L, "MAN", "Manchester DC", "GB", "M1 1AB"));
        // Destination "sw1a-1aa" — case + hyphen + case differ; should still hit LDN.
        WarehouseSelectionResult r = selector.selectNearest("ACME", "gb", "sw1a-1aa");
        assertEquals("COUNTRY_AND_POSTAL", r.getMatchReason());
        assertEquals(1L, r.getSelectedWarehouseId());
        assertEquals(7, r.getPostalPrefixLength(), "SW1A1AA vs SW1A1AA = 7-char prefix");
    }

    // ===== COUNTRY =====

    @Test
    void countryOnlyWhenPostalMissing() {
        stubTwoAttached(
                warehouse(1L, "EAST", "East DC", "US", "10001"),
                warehouse(2L, "TOR", "Toronto DC", "CA", "M5V"));
        WarehouseSelectionResult r = selector.selectNearest("ACME", "US", null);
        assertEquals("COUNTRY", r.getMatchReason(),
                "same-country match with no postal input");
        assertEquals(1L, r.getSelectedWarehouseId());
        assertEquals(0, r.getPostalPrefixLength());
    }

    @Test
    void countryOnlyWhenNoPostalPrefixMatches() {
        stubTwoAttached(
                warehouse(1L, "EAST", "East DC", "US", "10001"),
                warehouse(2L, "TOR", "Toronto DC", "CA", "M5V"));
        // 90001 shares no leading chars with 10001 — still country-match.
        WarehouseSelectionResult r = selector.selectNearest("ACME", "US", "90001");
        assertEquals("COUNTRY", r.getMatchReason());
        assertEquals(1L, r.getSelectedWarehouseId());
    }

    // ===== ANY =====

    @Test
    void anyFallbackDifferentCountry() {
        // Destination in DE; only US warehouse attached.
        stubTwoAttached(
                warehouse(1L, "EAST", "East DC", "US", "10001"),
                warehouse(2L, "TOR", "Toronto DC", "CA", "M5V"));
        WarehouseSelectionResult r = selector.selectNearest("ACME", "DE", "10115");
        assertEquals("ANY", r.getMatchReason(),
                "no same-country warehouse — fallback to any attached");
        // Winner picked by isDefault + createdAt order (default first) — since
        // the repository already returns them in that order and the tie-break
        // gives the default a +10 bump.
        assertEquals(1L, r.getSelectedWarehouseId(),
                "the default (isDefault=true) breaks the tie among fallbacks");
    }

    // ===== Default tie-break =====

    @Test
    void defaultWinsWhenScoresEqual() {
        // Both warehouses in US, both without postal → identical base score.
        // The default (id=1) wins by the +10 tie-break.
        Warehouse a = warehouse(1L, "EAST", "East DC", "US", null);
        Warehouse b = warehouse(2L, "WEST", "West DC", "US", null);
        when(clientWarehouseRepository.findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of(
                        cw(a.getId(), true),   // default
                        cw(b.getId(), false)));
        when(warehouseRepository.findAllById(anyIterable())).thenReturn(List.of(a, b));

        WarehouseSelectionResult r = selector.selectNearest("ACME", "US", null);
        assertEquals(1L, r.getSelectedWarehouseId());
        assertTrue(r.getCandidates().get(0).getIsDefault());
    }

    // ===== helpers =====

    private void stubTwoAttached(Warehouse a, Warehouse b) {
        when(clientWarehouseRepository.findByClientCodeIgnoreCaseOrderByIsDefaultDescCreatedAtAsc("ACME"))
                .thenReturn(List.of(cw(a.getId(), true), cw(b.getId(), false)));
        // N+1 fix (perf audit): impl now uses findAllById batch, not per-id findById.
        when(warehouseRepository.findAllById(anyIterable())).thenReturn(List.of(a, b));
    }

    private static Warehouse warehouse(Long id, String code, String name, String country, String postal) {
        return Warehouse.builder()
                .id(id).code(code).name(name)
                .ownerType(Warehouse.OWNER_PLATFORM)
                .active(true)
                .address(Address.builder().country(country).zip(postal).build())
                .build();
    }

    private static ClientWarehouse cw(Long warehouseId, boolean isDefault) {
        return ClientWarehouse.builder()
                .clientCode("ACME")
                .warehouseId(warehouseId)
                .isDefault(isDefault)
                .build();
    }
}
