package com.multiship.backend.service.refdata;

import com.multiship.backend.model.CountryRegionEntity;
import com.multiship.backend.model.IsoCurrencyEntity;
import com.multiship.backend.model.ReasonForExportEntity;
import com.multiship.backend.repository.CountryRegionRepository;
import com.multiship.backend.repository.IsoCurrencyRepository;
import com.multiship.backend.repository.ReasonForExportRepository;
import com.multiship.backend.util.CountryRegions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** V117 — loader orders reasons by sort_order, exposes currency codes,
 *  swaps CountryRegions' live map. */
class RefDataPlatformServiceTest {

    private ReasonForExportRepository reasonRepo;
    private IsoCurrencyRepository currencyRepo;
    private CountryRegionRepository countryRegionRepo;
    private RefDataPlatformService svc;

    @BeforeEach
    void setUp() {
        reasonRepo = mock(ReasonForExportRepository.class);
        currencyRepo = mock(IsoCurrencyRepository.class);
        countryRegionRepo = mock(CountryRegionRepository.class);
        svc = new RefDataPlatformService(reasonRepo, currencyRepo, countryRegionRepo);
    }

    @Test
    void reasonsLoadedSortedBySortOrder() {
        when(reasonRepo.findAll()).thenReturn(List.of(
                ReasonForExportEntity.builder().code("GIFT").label("Gift").sortOrder(20).build(),
                ReasonForExportEntity.builder().code("SALE").label("Sale").sortOrder(10).build(),
                ReasonForExportEntity.builder().code("NULL_SORT").label("Nulls").sortOrder(null).build()));
        when(currencyRepo.findAll()).thenReturn(List.of());
        when(countryRegionRepo.findAll()).thenReturn(List.of());

        svc.loadOnReady();

        List<ReasonForExportEntity> out = svc.listReasonsForExport();
        assertEquals("NULL_SORT", out.get(0).getCode(), "null sort treated as 0 → first");
        assertEquals("SALE", out.get(1).getCode());
        assertEquals("GIFT", out.get(2).getCode());
    }

    @Test
    void currencyCodesSurfaceAsSet() {
        when(reasonRepo.findAll()).thenReturn(List.of());
        when(currencyRepo.findAll()).thenReturn(List.of(
                IsoCurrencyEntity.builder().code("USD").name("US Dollar").build(),
                IsoCurrencyEntity.builder().code("EUR").name("Euro").build()));
        when(countryRegionRepo.findAll()).thenReturn(List.of());

        svc.loadOnReady();

        assertTrue(svc.isoCurrencyCodes().contains("USD"));
        assertTrue(svc.isoCurrencyCodes().contains("EUR"));
        assertEquals(2, svc.isoCurrencyCodes().size());
    }

    @Test
    void countryRegionLoadSwapsLiveMap() {
        when(reasonRepo.findAll()).thenReturn(List.of());
        when(currencyRepo.findAll()).thenReturn(List.of());
        when(countryRegionRepo.findAll()).thenReturn(List.of(
                CountryRegionEntity.builder().countryCode("ZZ").regionCode("ASIA").build(),
                CountryRegionEntity.builder().countryCode("XX").regionCode("EUROPE").build()));

        svc.loadOnReady();

        // Live map now carries the DB rows, translated via CountryRegions.labelFor.
        assertEquals("Asia", CountryRegions.regionOf("ZZ"));
        assertEquals("Europe", CountryRegions.regionOf("XX"));

        // Restore bootstrap for other tests — not strictly needed since the test
        // DB load wins, but keeps the suite deterministic if run in isolation.
        CountryRegions.setRegionByCode(java.util.Map.of("US", "North America"));
    }

    @Test
    void countryRegionLoadKeepsBootstrapOnEmpty() {
        when(reasonRepo.findAll()).thenReturn(List.of());
        when(currencyRepo.findAll()).thenReturn(List.of());
        when(countryRegionRepo.findAll()).thenReturn(List.of());
        // Prime bootstrap-equivalent for this test.
        CountryRegions.setRegionByCode(java.util.Map.of("US", "North America"));

        svc.loadOnReady();

        assertEquals("North America", CountryRegions.regionOf("US"));
    }

    @Test
    void loadSwallowsRepoFailurePerTable() {
        when(reasonRepo.findAll()).thenThrow(new RuntimeException("DB down"));
        when(currencyRepo.findAll()).thenThrow(new RuntimeException("DB down"));
        when(countryRegionRepo.findAll()).thenThrow(new RuntimeException("DB down"));
        svc.loadOnReady();  // must not throw
        assertTrue(svc.listReasonsForExport().isEmpty());
        assertTrue(svc.listIsoCurrencies().isEmpty());
    }
}
