package com.multiship.backend.controller;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** BUG-156 — the Since / Until boxes are read in the operator's zone, not as UTC wall-clock text. */
class AuditLogControllerRangeTest {

    private static final LocalDateTime FALLBACK = LocalDateTime.of(1970, 1, 1, 0, 0);

    @Test
    void anOffsetIsConvertedToUtc() {
        // 16:00 in India is 10:30 UTC — the row written at 10:30Z must match.
        assertEquals(LocalDateTime.of(2026, 10, 25, 10, 30),
                AuditLogController.parseOrDefault("2026-10-25T16:00:00+05:30", FALLBACK, "since"));
        assertEquals(LocalDateTime.of(2026, 10, 25, 10, 30),
                AuditLogController.parseOrDefault("2026-10-25T10:30:00.000Z", FALLBACK, "since"));
    }

    @Test
    void aZoneLessValueIsReadAsUtcAndGarbageIsRefused() {
        assertEquals(LocalDateTime.of(2026, 10, 25, 10, 30),
                AuditLogController.parseOrDefault("2026-10-25T10:30", FALLBACK, "since"));
        assertEquals(FALLBACK, AuditLogController.parseOrDefault("  ", FALLBACK, "since"));
        assertThrows(IllegalArgumentException.class,
                () -> AuditLogController.parseOrDefault("yesterday", FALLBACK, "since"));
    }
}
