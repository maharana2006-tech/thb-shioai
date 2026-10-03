package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** V117 — ISO 4217 currency registry (seeded only with the 10 codes shioai uses today). */
@Entity
@Table(name = "iso_currency")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IsoCurrencyEntity {

    @Id
    @Column(length = 3, nullable = false)
    private String code;

    @Column(length = 80)
    private String name;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
