package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** V117 — country → shipping region mapping. Mirrors
 *  {@link com.multiship.backend.util.CountryRegions}; the util picks the
 *  DB map up at ApplicationReadyEvent via CountryRegionPlatformService. */
@Entity
@Table(name = "country_region")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CountryRegionEntity {

    @Id
    @Column(name = "country_code", length = 2, nullable = false)
    private String countryCode;

    @Column(name = "region_code", length = 32, nullable = false)
    private String regionCode;
}
