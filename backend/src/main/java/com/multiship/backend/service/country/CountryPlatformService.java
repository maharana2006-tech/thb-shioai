package com.multiship.backend.service.country;

import com.multiship.backend.model.CountryEntity;
import com.multiship.backend.repository.CountryRepository;
import com.multiship.backend.util.UsTerritoryNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * V111 — loads the DB-driven US-territory list from the platform
 * {@code country} table and swaps {@link UsTerritoryNormalizer}'s
 * volatile set at {@link ApplicationReadyEvent}. Adding a new territory
 * is now one SQL row, not a redeploy.
 *
 * <p>Zero caller changes elsewhere — every existing consumer of
 * {@code UsTerritoryNormalizer.US_TERRITORY_CODES} picks up the swap
 * for free. See the migration header for scope.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CountryPlatformService {

    private final CountryRepository repo;

    @EventListener(ApplicationReadyEvent.class)
    public void loadUsTerritoriesIntoNormalizer() {
        try {
            Set<String> codes = repo.findByIsUsTerritoryTrue().stream()
                    .map(CountryEntity::getCountryCode)
                    .filter(c -> c != null && !c.isBlank())
                    .map(c -> c.trim().toUpperCase())
                    .collect(Collectors.toUnmodifiableSet());
            if (codes.isEmpty()) {
                log.info("country: no is_us_territory rows seeded; UsTerritoryNormalizer keeps its bootstrap default");
                return;
            }
            UsTerritoryNormalizer.setUsTerritoryCodes(codes);
            log.info("country: swapped UsTerritoryNormalizer set to {} DB-driven codes {}", codes.size(), codes);
        } catch (Exception ex) {
            // Any DB-side failure (missing table on an old install, migration
            // mid-run, etc.) leaves the baked-in bootstrap in place.
            log.warn("country: could not load is_us_territory rows; UsTerritoryNormalizer stays on bootstrap default: {}",
                    ex.getMessage());
        }
    }
}
