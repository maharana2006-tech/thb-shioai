package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Sprint 38 — saved recipient / address book row. An operator who ships
 * to the same 20 customers a day can save each one here and pick from a
 * search dropdown instead of retyping. Per-customer scoped (via
 * {@link #ownerCustomerNo}) so a 3PL managing multiple clients keeps
 * each client's book separate; null owner = platform-wide (visible to
 * every operator).
 *
 * <p>Deduplication: {@link #dedupHash} is a hash of
 * {@code lower(name)|lower(addressLine1)|lower(postalCode)} — the
 * service refuses to persist a second entry with the same hash for the
 * same owner. Prevents "45 copies of Acme Warehouse".
 */
@Entity
@Table(name = "saved_recipients",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"owner_customer_no", "dedup_hash"},
                name = "uk_saved_recipient_owner_hash"))
@Data
public class SavedRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optional per-customer scope. Null = platform-wide. */
    @Column(name = "owner_customer_no", length = 50)
    private String ownerCustomerNo;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "company", length = 200)
    private String company;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "phone_country_code", length = 5)
    private String phoneCountryCode;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "address_line1", nullable = false, length = 200)
    private String addressLine1;

    @Column(name = "address_line2", length = 200)
    private String addressLine2;

    @Column(name = "address_line3", length = 200)
    private String addressLine3;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "postal_code", nullable = false, length = 20)
    private String postalCode;

    @Column(name = "country_code", nullable = false, length = 5)
    private String countryCode;

    /** True when the address is a residence — flips carrier residential rating. */
    @Column(name = "residential")
    private Boolean residential;

    /** Free-form tag (e.g. "VIP", "returns", "wholesale"). Null when unset. */
    @Column(name = "tag", length = 50)
    private String tag;

    /**
     * Hash of {@code lower(name)|lower(addressLine1)|lower(postalCode)}.
     * Enforces the (owner, dedupHash) unique constraint so the same
     * owner can't stack duplicates.
     */
    @Column(name = "dedup_hash", nullable = false, length = 64)
    private String dedupHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
