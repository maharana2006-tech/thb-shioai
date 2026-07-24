package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Per-client ship-to restriction. A client can be configured either as
 *   ALLOW list — only listed destinations are permitted; anything else 422s.
 *   DENY  list — listed destinations are blocked; everything else is fine.
 *
 * Determined by the mode column, kept consistent per-client in the service
 * layer (all rows for a client share the same mode). No rows for a client
 * means "no restriction — ship anywhere".
 *
 * unique(client_code, country) — a destination country can only be listed
 * once per client.
 */
@Entity
@Table(name = "client_destination_rule",
        uniqueConstraints = @UniqueConstraint(name = "uq_client_destination_rule",
                columnNames = {"client_code", "country"}),
        indexes = @Index(name = "idx_client_destination_rule_client", columnList = "client_code"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientDestinationRule {

    public static final String MODE_ALLOW = "ALLOW";
    public static final String MODE_DENY = "DENY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    /** ALLOW | DENY. Same for every row belonging to one client. */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String mode = MODE_ALLOW;

    /** ISO-3166 alpha-2, upper-case. */
    @Column(nullable = false, length = 2)
    private String country;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
