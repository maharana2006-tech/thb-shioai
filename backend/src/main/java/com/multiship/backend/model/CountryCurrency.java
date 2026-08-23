package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * F6-B1 — country-to-currency lookup for the ShipmentDefaultsResolver
 * currency-fallback chain.
 *
 * <p>Populated by the V23 Flyway seed with all 249 ISO 3166-1 alpha-2 →
 * ISO 4217 mappings. The resolver reads this table when a Client has NOT
 * set {@code defaultCurrency}; the fallback picks the currency of the
 * client's ship-from country. Anything not in the table means the
 * resolver falls through further (domestic USD hardcode or international
 * throw).
 *
 * <p>Country codes are always uppercase ISO 3166 alpha-2 (2 chars).
 * Currency codes are always uppercase ISO 4217 (3 chars).
 */
@Entity
@Table(name = "country_currency")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CountryCurrency {

    @Id
    @Column(name = "country_code", length = 2, nullable = false)
    private String countryCode;

    @Column(name = "currency_code", length = 3, nullable = false)
    private String currencyCode;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
