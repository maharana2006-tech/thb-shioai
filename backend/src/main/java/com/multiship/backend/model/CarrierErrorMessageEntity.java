package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** V118 — carrier rejection pattern → humanized message mapping. */
@Entity
@Table(name = "carrier_error_message")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarrierErrorMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** NULL = any carrier. Else canonical code (FEDEX / UPS / USPS / DHL). */
    @Column(length = 32)
    private String carrier;

    /** Pipe-separated OR tokens (e.g. {@code POSTAL|ZIP}). Matched against
     *  the uppercased raw message. */
    @Column(name = "match_any_of", nullable = false, columnDefinition = "TEXT")
    private String matchAnyOf;

    /** Operator-facing sentence. {@code {carrier}} is substituted at runtime
     *  with the pretty carrier name (FedEx / UPS / USPS / DHL / "The carrier"). */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String humanized;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
