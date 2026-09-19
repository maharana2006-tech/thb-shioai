package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * PR-Printer-R8a — free-form tag per printer (M2M). See V71.
 *
 * <p>{@code tag} is stored lowercased for stable dedupe + autocomplete
 * matching; the FE may title-case on display. Uniqueness is enforced
 * per (printer_id, tag).
 */
@Entity
@Table(name = "printer_tags")
@Getter
@Setter
@NoArgsConstructor
public class PrinterTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "printer_id", nullable = false)
    private Long printerId;

    @Column(name = "tag", nullable = false, length = 60)
    private String tag;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
