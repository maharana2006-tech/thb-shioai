package com.multiship.backend.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * One importer + broker "identity" a client uses, applied to a SET of
 * destination countries (built region-by-region in the UI). A client shipping
 * to all of the EU with one importer/broker keeps a single profile whose
 * {@link #countries} set holds every EU country. At label time the shipment's
 * importer/broker resolve automatically from (client, ship-to country) by
 * finding the profile whose country set contains that destination.
 *
 * No country may belong to two profiles of the same client — enforced in the
 * service layer on save.
 */
@Entity
@Table(name = "client_customs_profile",
        indexes = @Index(name = "idx_customs_profile_client", columnList = "client_code"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientCustomsProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning client (matches clients.client_code). */
    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    /**
     * Destination countries this profile applies to. A real child entity (not
     * an @ElementCollection) so each row carries a denormalized client_code
     * and the DB enforces unique(client_code, country) — no country can ever
     * be double-booked across a client's profiles, even under concurrency.
     */
    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Set<CustomsProfileCountry> countryLinks = new LinkedHashSet<>();

    /** Sorted view of the covered country codes. */
    public Set<String> getCountries() {
        return countryLinks.stream().map(CustomsProfileCountry::getCountry)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Sync the link set to the given codes (diff-based: unchanged countries
     * keep their rows so Hibernate never re-inserts an existing
     * (client_code, country) pair before deleting it, which would trip the
     * unique constraint mid-flush).
     */
    public void syncCountries(Set<String> codes) {
        countryLinks.removeIf(link -> !codes.contains(link.getCountry()));
        Set<String> existing = countryLinks.stream()
                .map(CustomsProfileCountry::getCountry).collect(Collectors.toSet());
        for (String code : codes) {
            if (!existing.contains(code)) {
                countryLinks.add(CustomsProfileCountry.builder()
                        .profile(this).clientCode(clientCode).country(code).build());
            }
        }
        // Keep the denormalized client_code aligned with the owner.
        countryLinks.forEach(link -> link.setClientCode(clientCode));
    }

    // ===== Importer of record =====
    /**
     * Who acts as Importer of Record:
     * BUSINESS = a fixed registered entity (DDP / B2B — the fields below);
     * RECEIVER = the order's consignee is the importer (DAP / B2C — the
     * carrier collects their KYC; the identity fields below stay empty).
     */
    @Column(name = "importer_type", length = 10)
    @Builder.Default
    private String importerType = "BUSINESS";
    @Column(name = "importer_name", length = 200) private String importerName;
    @Column(name = "importer_contact", length = 200) private String importerContact;
    @Column(name = "importer_country", length = 10) private String importerCountry;
    @Column(name = "importer_address1", length = 255) private String importerAddress1;
    @Column(name = "importer_address2", length = 255) private String importerAddress2;
    @Column(name = "importer_phone", length = 50) private String importerPhone;
    @Column(name = "importer_city", length = 120) private String importerCity;
    @Column(name = "importer_state", length = 120) private String importerState;
    @Column(name = "importer_postcode", length = 20) private String importerPostcode;
    // tax identity (region-dependent — EORI/IOSS matter for the EU)
    @Column(name = "importer_tax_id", length = 60) private String importerTaxId;
    @Column(name = "importer_tax_id_type", length = 20) private String importerTaxIdType;
    @Column(name = "importer_eori", length = 40) private String importerEori;
    @Column(name = "importer_ioss", length = 40) private String importerIoss;
    @Column(name = "importer_company_reg", length = 60) private String importerCompanyReg;
    // India-specific (IEC is mandatory for business imports into IN; KYC uses GSTIN/PAN)
    @Column(name = "importer_iec", length = 40) private String importerIec;
    @Column(name = "importer_gstin", length = 40) private String importerGstin;

    // ===== Customs broker =====
    @Column(name = "broker_name", length = 200) private String brokerName;
    @Column(name = "broker_company", length = 200) private String brokerCompany;
    @Column(name = "broker_country", length = 10) private String brokerCountry;
    @Column(name = "broker_address1", length = 255) private String brokerAddress1;
    @Column(name = "broker_address2", length = 255) private String brokerAddress2;
    @Column(name = "broker_phone", length = 50) private String brokerPhone;
    @Column(name = "broker_city", length = 120) private String brokerCity;
    @Column(name = "broker_state", length = 120) private String brokerState;
    @Column(name = "broker_postcode", length = 20) private String brokerPostcode;
    @Column(name = "broker_id", length = 40) private String brokerId;
    @Column(name = "broker_license", length = 60) private String brokerLicense;

    // ===== Shipment defaults for this client + destinations =====
    @Column(name = "incoterms", length = 10) private String incoterms;
    @Column(name = "duties_bill_to", length = 20) private String dutiesBillTo;
    @Column(name = "duties_account", length = 60) private String dutiesAccount;
    @Column(name = "reason_for_export", length = 40) private String reasonForExport;
    @Column(name = "currency", length = 3) private String currency;

    // ===== Optional carrier account for this client+destinations =====
    @Column(name = "account_carrier", length = 20) private String accountCarrier;
    @Column(name = "account_no", length = 60) private String accountNo;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
