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

import java.time.LocalDateTime;

/**
 * One destination country tag on a {@link ClientAllowedService} row: the
 * client may only use the service when shipping TO one of the listed
 * countries.
 *
 * <p>Empty child-set semantics: when a {@code ClientAllowedService} has no
 * rows in this table, it is allowed for <em>any</em> destination — the
 * unrestricted default. Adding at least one row switches the row to
 * whitelist mode: only the listed destinations are permitted.
 *
 * <p>{@code client_code} is denormalised from the parent's row so the DB
 * can enforce {@code unique(client_code, allowed_service_id, country)}
 * cheaply and so query filters can skip the join to the parent.
 */
@Entity
@Table(name = "client_allowed_service_destination",
        uniqueConstraints = @UniqueConstraint(name = "uq_client_allowed_svc_dest",
                columnNames = {"allowed_service_id", "country"}),
        indexes = {
                @Index(name = "idx_client_allowed_svc_dest_svc", columnList = "allowed_service_id"),
                @Index(name = "idx_client_allowed_svc_dest_client", columnList = "client_code"),
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientAllowedServiceDestination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@link ClientAllowedService#getId()} — plain long so bulk delete
     *  when the parent goes away is a one-shot query. */
    @Column(name = "allowed_service_id", nullable = false)
    private Long allowedServiceId;

    /** Denormalised from the parent for constraint indexing. */
    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    /** ISO-3166 alpha-2, upper-case. */
    @Column(nullable = false, length = 2)
    private String country;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
