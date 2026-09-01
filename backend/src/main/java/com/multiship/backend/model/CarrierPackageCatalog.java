package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A carrier's published, fixed-spec package (UPS Letter, FedEx Pak, USPS
 * Flat Rate Box…) — dimensions/weight caps the carrier itself defines, not
 * something any carrier API returns live. Previously hardcoded per-carrier
 * in {@code UpsConnector}/{@code FedExConnector}/{@code StampsConnector}/
 * {@code DhlConnector}; moved here (V32) so ops can correct or extend the
 * catalogue without a code deploy, mirroring {@link CarrierShippingLimit}.
 *
 * <p>Connectors' {@code listPackages(...)} read this table instead of an
 * in-code list; {@code ShippingConfigService.syncPackagesFromCarrier} still
 * only writes {@code package_preset} rows after a live credential check —
 * this table changes where the offerings come from, not the live-only
 * write policy.
 */
@Entity
@Table(name = "carrier_package_catalog",
        uniqueConstraints = @UniqueConstraint(name = "uk_carrier_package_catalog_key",
                columnNames = {"carrier_code", "code"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierPackageCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UPS | FEDEX | DHL | USPS (canonical carrier code). */
    @Column(name = "carrier_code", nullable = false, length = 20)
    private String carrierCode;

    /** Carrier's own package code (e.g. UPS "01", FedEx "FEDEX_PAK"). */
    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "length", precision = 8, scale = 2)
    private BigDecimal length;

    @Column(name = "width", precision = 8, scale = 2)
    private BigDecimal width;

    @Column(name = "height", precision = 8, scale = 2)
    private BigDecimal height;

    @Column(name = "max_weight", precision = 8, scale = 2)
    private BigDecimal maxWeight;

    @Column(name = "flat_rate", nullable = false)
    @Builder.Default
    private Boolean flatRate = false;

    /** DOMESTIC | INTERNATIONAL | BOTH. */
    @Column(name = "scope", nullable = false, length = 20)
    @Builder.Default
    private String scope = "BOTH";

    /** True only for packaging offered solely from a US/PR origin (e.g.
     *  FedEx One Rate boxes). Connectors that gate on origin (FedEx, and
     *  USPS's carrier-wide US-only check) apply this the same way the old
     *  hardcoded {@code us} check did. */
    @Column(name = "us_domestic_only", nullable = false)
    @Builder.Default
    private Boolean usDomesticOnly = false;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 100;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;
}
