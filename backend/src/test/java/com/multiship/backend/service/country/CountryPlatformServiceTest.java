package com.multiship.backend.service.country;

import com.multiship.backend.model.CountryEntity;
import com.multiship.backend.repository.CountryRepository;
import com.multiship.backend.util.UsTerritoryNormalizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** V111 — DB rows override the bootstrap set; empty / failing DB keeps bootstrap. */
class CountryPlatformServiceTest {

    private final Set<String> originalBootstrap = UsTerritoryNormalizer.US_TERRITORY_CODES;

    @AfterEach
    void restore() {
        // Restore the baked-in default so other tests see the full six codes.
        UsTerritoryNormalizer.setUsTerritoryCodes(originalBootstrap);
    }

    @Test
    void loadSwapsSetWhenRowsPresent() {
        CountryRepository repo = mock(CountryRepository.class);
        when(repo.findByIsUsTerritoryTrue()).thenReturn(List.of(
                CountryEntity.builder().countryCode("PR").isUsTerritory(true).build(),
                CountryEntity.builder().countryCode("VI").isUsTerritory(true).build(),
                // Extra DB-added territory the Java default doesn't know about.
                CountryEntity.builder().countryCode("XX").isUsTerritory(true).build()));
        new CountryPlatformService(repo).loadUsTerritoriesIntoNormalizer();
        assertEquals(Set.of("PR", "VI", "XX"), UsTerritoryNormalizer.US_TERRITORY_CODES);
    }

    @Test
    void loadKeepsBootstrapWhenRepoReturnsEmpty() {
        CountryRepository repo = mock(CountryRepository.class);
        when(repo.findByIsUsTerritoryTrue()).thenReturn(List.of());
        new CountryPlatformService(repo).loadUsTerritoriesIntoNormalizer();
        assertTrue(UsTerritoryNormalizer.US_TERRITORY_CODES.contains("PR"),
                "bootstrap codes must stay in place when DB is empty");
        assertTrue(UsTerritoryNormalizer.US_TERRITORY_CODES.contains("UM"));
    }

    @Test
    void loadSwallowsRepoFailureAndKeepsBootstrap() {
        CountryRepository repo = mock(CountryRepository.class);
        when(repo.findByIsUsTerritoryTrue()).thenThrow(new RuntimeException("DB down"));
        // Must not throw out of the ApplicationReadyEvent handler.
        new CountryPlatformService(repo).loadUsTerritoriesIntoNormalizer();
        assertTrue(UsTerritoryNormalizer.US_TERRITORY_CODES.contains("PR"));
    }
}
