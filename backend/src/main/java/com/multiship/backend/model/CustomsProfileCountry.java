package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One destination country covered by a {@link ClientCustomsProfile}.
 *
 * clientCode is deliberately DENORMALIZED from the parent profile so the
 * database itself can enforce the business rule "no country may belong to two
 * profiles of the same client" via unique(client_code, country) — the
 * service-layer pre-check gives friendly messages, this constraint makes the
 * rule race-proof. Kept in sync in ClientCustomsProfileServiceImpl.upsert.
 *
 * NOTE: intentionally NOT @Data — a bidirectional link inside equals/toString
 * would recurse into the parent.
 */
@Entity
@Table(name = "customs_profile_country",
        uniqueConstraints = @UniqueConstraint(name = "uq_customs_country_client",
                columnNames = {"client_code", "country"}),
        indexes = @Index(name = "idx_customs_country_profile", columnList = "profile_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomsProfileCountry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private ClientCustomsProfile profile;

    /** Mirrors profile.clientCode — exists purely for the unique constraint. */
    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    /** ISO-3166 alpha-2, upper-case. */
    @Column(nullable = false, length = 2)
    private String country;
}
