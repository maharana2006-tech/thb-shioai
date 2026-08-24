package com.multiship.backend.repository;

import com.multiship.backend.model.CountryCurrency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * F6-B1 — read-side lookup for the ShipmentDefaultsResolver currency
 * fallback. Reads only; the seed is Flyway-managed (V23), so no create /
 * update / delete surface is exposed here.
 */
@Repository
public interface CountryCurrencyRepository extends JpaRepository<CountryCurrency, String> {

    /** Case-insensitive so the caller can pass whatever casing the client
     *  entity has (Client.shipFrom.countryCode is uppercased on write,
     *  but not enforced at the DB layer). */
    Optional<CountryCurrency> findByCountryCodeIgnoreCase(String countryCode);
}
