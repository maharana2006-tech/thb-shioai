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
 * Per-client destination-country alias: maps whatever the ERP sends
 * ("USA", "United States", "US-DOM") to the canonical ISO-3166 alpha-2
 * ("US"). Order intake looks this up and writes the canonical value onto
 * the order; the raw code goes to {@link OrderRawCodes}.
 */
@Entity
@Table(name = "client_dest_country_map",
        uniqueConstraints = @UniqueConstraint(name = "uq_client_dest_country_code",
                columnNames = {"client_code", "erp_code"}),
        indexes = @Index(name = "idx_client_dest_country_client", columnList = "client_code"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientDestCountryMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_code", nullable = false, length = 50)
    private String clientCode;

    /** The raw destination code as the ERP sends it. */
    @Column(name = "erp_code", nullable = false, length = 40)
    private String erpCode;

    /** Canonical ISO-3166 alpha-2, upper-case. */
    @Column(name = "iso2", nullable = false, length = 2)
    private String iso2;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
