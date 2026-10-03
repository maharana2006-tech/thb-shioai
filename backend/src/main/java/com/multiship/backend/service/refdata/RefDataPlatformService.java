package com.multiship.backend.service.refdata;

import com.multiship.backend.model.CountryRegionEntity;
import com.multiship.backend.model.IsoCurrencyEntity;
import com.multiship.backend.model.ReasonForExportEntity;
import com.multiship.backend.repository.CountryRegionRepository;
import com.multiship.backend.repository.IsoCurrencyRepository;
import com.multiship.backend.repository.ReasonForExportRepository;
import com.multiship.backend.util.CountryRegions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * V117 — one platform-service bean loads all three reference-data tables
 * at {@link ApplicationReadyEvent}:
 *
 * <ul>
 *   <li>reason_for_export → {@link #listReasonsForExport}</li>
 *   <li>iso_currency → {@link #listIsoCurrencies}</li>
 *   <li>country_region → swaps {@link CountryRegions}' live map</li>
 * </ul>
 *
 * <p>Each cache is volatile; empty / failing DB keeps the prior value
 * (seeded bootstrap on first load; last-known-good after that).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefDataPlatformService {

    private final ReasonForExportRepository reasonRepo;
    private final IsoCurrencyRepository currencyRepo;
    private final CountryRegionRepository countryRegionRepo;

    private volatile List<ReasonForExportEntity> reasons = List.of();
    private volatile List<IsoCurrencyEntity> currencies = List.of();

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnReady() {
        loadReasons();
        loadCurrencies();
        loadCountryRegions();
    }

    private void loadReasons() {
        try {
            // Stream + sorted copies to a fresh list; findAll's backing
            // list may itself be immutable (e.g. mocked List.of(...)) so
            // an in-place sort() would UnsupportedOperationException.
            reasons = reasonRepo.findAll().stream()
                    .sorted((a, b) -> Integer.compare(
                            a.getSortOrder() == null ? 0 : a.getSortOrder(),
                            b.getSortOrder() == null ? 0 : b.getSortOrder()))
                    .toList();
            log.info("refdata: loaded {} reason_for_export row(s)", reasons.size());
        } catch (Exception ex) {
            log.warn("refdata: reason_for_export load failed: {}", ex.getMessage());
        }
    }

    private void loadCurrencies() {
        try {
            currencies = currencyRepo.findAll();
            log.info("refdata: loaded {} iso_currency row(s)", currencies.size());
        } catch (Exception ex) {
            log.warn("refdata: iso_currency load failed: {}", ex.getMessage());
        }
    }

    private void loadCountryRegions() {
        try {
            List<CountryRegionEntity> rows = countryRegionRepo.findAll();
            if (rows.isEmpty()) {
                log.info("refdata: no country_region rows; CountryRegions keeps bootstrap");
                return;
            }
            Map<String, String> map = new LinkedHashMap<>();
            for (CountryRegionEntity r : rows) {
                map.put(r.getCountryCode(), CountryRegions.labelFor(r.getRegionCode()));
            }
            CountryRegions.setRegionByCode(map);
            log.info("refdata: swapped CountryRegions set to {} DB-driven country→region mappings", map.size());
        } catch (Exception ex) {
            log.warn("refdata: country_region load failed; CountryRegions keeps prior map: {}", ex.getMessage());
        }
    }

    public List<ReasonForExportEntity> listReasonsForExport() { return reasons; }

    public List<IsoCurrencyEntity> listIsoCurrencies() { return currencies; }

    public Set<String> isoCurrencyCodes() {
        return currencies.stream().map(IsoCurrencyEntity::getCode).collect(Collectors.toUnmodifiableSet());
    }
}
