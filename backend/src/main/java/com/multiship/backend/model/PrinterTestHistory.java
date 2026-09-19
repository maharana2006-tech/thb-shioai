package com.multiship.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * PR-Printer-R9.5a — one row per test-print attempt (success or
 * failure). The current {@link Printer#getLastTestOk()} et al only
 * record the LATEST attempt; this table's rolling history feeds the
 * FE PrinterDetailsPanel so ops can diagnose intermittent flakes.
 *
 * <p>Cascade delete via V72 FK: dropping a printer drops its history.
 */
@Entity
@Table(name = "printer_test_history")
@Getter
@Setter
@NoArgsConstructor
public class PrinterTestHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "printer_id", nullable = false)
    private Long printerId;

    @Column(name = "tested_at", nullable = false)
    private LocalDateTime testedAt;

    @Column(name = "ok", nullable = false)
    private Boolean ok;

    /** TEXT — matches the {@code lastTestMessage} column's shape.
     *  Nullable because a successful send might not have a message. */
    @Column(name = "message", columnDefinition = "text")
    private String message;

    /** Nullable — scheduled or system-triggered tests may have no user. */
    @Column(name = "tested_by", length = 120)
    private String testedBy;
}
