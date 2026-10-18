package com.multiship.backend.repository;

import com.multiship.backend.model.ShippingService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShippingServiceRepository extends JpaRepository<ShippingService, Long> {

    List<ShippingService> findAllByOrderByCarrierAscSortOrderAsc();

    List<ShippingService> findByOriginCountryIgnoreCaseOrderByCarrierAscSortOrderAsc(String originCountry);

    List<ShippingService> findByCarrierIgnoreCaseAndEnabledTrueOrderBySortOrderAsc(String carrier);

    /**
     * BUG-017 — the uniqueness key is {@code (carrier, service_code, origin_country)},
     * so this (carrier, service_code)-only lookup can legitimately match 2+ rows
     * (e.g. {@code GROUND_HOME_DELIVERY} exists for US + CA). The pre-fix derived
     * finder threw {@link org.springframework.dao.IncorrectResultSizeDataAccessException}
     * on close-out days busy enough to touch a cross-country code, 500'ing
     * {@code GET /manifests/open}. The two callers
     * ({@code ManifestServiceImpl#resolveByBilledAccount},
     * {@code RateShopServiceImpl#resolveServiceId}) only read intrinsic fields
     * ({@code is_express}, {@code id}) that are identical across origin-country
     * siblings, so any-one-wins is safe. Deterministic pick: lowest id (first).
     */
    @Query("SELECT s FROM ShippingService s WHERE LOWER(s.carrier) = LOWER(?1) "
            + "AND LOWER(s.serviceCode) = LOWER(?2) ORDER BY s.id ASC")
    List<ShippingService> findAllByCarrierAndServiceCode(String carrier, String serviceCode);

    /**
     * See {@link #findAllByCarrierAndServiceCode}. Returns the first match for
     * callers that only need an any-one-wins row.
     */
    default Optional<ShippingService> findByCarrierIgnoreCaseAndServiceCodeIgnoreCase(
            String carrier, String serviceCode) {
        List<ShippingService> rows = findAllByCarrierAndServiceCode(carrier, serviceCode);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Sync upsert key: one row per (carrier, code, origin country). */
    Optional<ShippingService> findByCarrierIgnoreCaseAndServiceCodeIgnoreCaseAndOriginCountryIgnoreCase(
            String carrier, String serviceCode, String originCountry);

    @Query("select distinct s.originCountry from ShippingService s where s.originCountry is not null order by s.originCountry")
    List<String> findDistinctOriginCountries();
}
