package com.multiship.backend.model;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A client (customer) the shipping team ships on behalf of. Orders link to a
 * client through COALESCE(order.tenant_id, order.cust_no) = client_code, and
 * carrier accounts link through carrier_account_ref.customer_no = client_code.
 *
 * The client owns two addresses used when generating its labels:
 *   - shipFrom      — the origin printed in the label's FROM block
 *   - returnAddress — where undeliverable parcels / returns go back to
 * shipFrom reuses the original flat address columns, so existing client
 * addresses become their ship-from with no migration.
 */
@Entity
@Table(name = "clients")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Client {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique uppercase code linking orders and carrier accounts (e.g. ARHDEV, MA1885). */
    @Column(name = "client_code", unique = true, nullable = false, length = 50)
    private String clientCode;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String email;

    @Column(length = 50)
    private String phone;

    /** Origin address printed in the label's FROM block. Reuses the original columns. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "name", column = @Column(name = "ship_from_name", length = 255)),
            @AttributeOverride(name = "line1", column = @Column(name = "address_line1", length = 255)),
            @AttributeOverride(name = "line2", column = @Column(name = "ship_from_line2", length = 255)),
            @AttributeOverride(name = "city", column = @Column(name = "city", length = 100)),
            @AttributeOverride(name = "state", column = @Column(name = "state", length = 50)),
            @AttributeOverride(name = "zip", column = @Column(name = "zip_code", length = 20)),
            @AttributeOverride(name = "country", column = @Column(name = "country_code", length = 10)),
            @AttributeOverride(name = "phone", column = @Column(name = "ship_from_phone", length = 50)),
    })
    private Address shipFrom;

    /** Where returns / undeliverable parcels go. May mirror shipFrom. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "name", column = @Column(name = "return_name", length = 255)),
            @AttributeOverride(name = "line1", column = @Column(name = "return_line1", length = 255)),
            @AttributeOverride(name = "line2", column = @Column(name = "return_line2", length = 255)),
            @AttributeOverride(name = "city", column = @Column(name = "return_city", length = 100)),
            @AttributeOverride(name = "state", column = @Column(name = "return_state", length = 50)),
            @AttributeOverride(name = "zip", column = @Column(name = "return_zip", length = 20)),
            @AttributeOverride(name = "country", column = @Column(name = "return_country", length = 10)),
            @AttributeOverride(name = "phone", column = @Column(name = "return_phone", length = 50)),
    })
    private Address returnAddress;

    /** When true, the return address mirrors ship-from (the common case). */
    @Column(name = "return_same_as_ship_from")
    @Default
    private Boolean returnSameAsShipFrom = true;

    /** ACTIVE clients generate labels; INACTIVE clients are suspended. */
    @Column(nullable = false, length = 20)
    @Default
    private String status = STATUS_ACTIVE;

    // ===== Sprint 50 Tier 1 finding #4 — first-class per-tenant defaults =====
    //
    // Populated by the client admin page; consulted by the label / rate /
    // customs pipelines before falling back to platform hardcodes. When
    // NULL, callers behave as they did pre-Sprint-50 (silent hardcode);
    // once ops backfills every active client's defaults, consumers can
    // graduate to Tier 1-A's UNIT_REQUIRED / CURRENCY_REQUIRED loud fails
    // on absence. Kept nullable for zero-downtime deploy.

    /** ISO 4217 3-letter code (USD, EUR, GBP, …). */
    @Column(name = "default_currency", length = 3)
    private String defaultCurrency;

    /** LB / KG / OZ / G. Matches the UnitConverter enum. */
    @Column(name = "default_weight_unit", length = 4)
    private String defaultWeightUnit;

    /** IN / CM / MM. Matches the UnitConverter enum. */
    @Column(name = "default_dim_unit", length = 4)
    private String defaultDimUnit;

    /** IANA tz identifier (e.g. "America/New_York"). Used for cutoff calc + display. */
    @Column(name = "timezone", length = 50)
    private String timezone;

    /** ISO-3166-1 alpha-2 country code (US, IN, GB, …) for the client's default ship-from country. */
    @Column(name = "default_origin_country", length = 2)
    private String defaultOriginCountry;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public boolean isActive() {
        return STATUS_ACTIVE.equalsIgnoreCase(status);
    }

    /** The address returns actually use — return address if set, else ship-from. */
    public Address effectiveReturnAddress() {
        if (Boolean.FALSE.equals(returnSameAsShipFrom) && returnAddress != null && returnAddress.hasValue()) {
            return returnAddress;
        }
        return shipFrom;
    }
}
