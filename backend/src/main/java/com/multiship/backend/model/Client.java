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
