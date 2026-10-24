package com.multiship.backend.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-010 — the clamp pair governs per-page row caps for every list
 * endpoint. These pin the two ceilings and the "size &lt;= 0 means
 * default" collapse behaviour so neither accidentally regresses.
 */
class PaginationDefaultsTest {

    @Test
    void clamp_capsAtMaxSize() {
        assertEquals(PaginationDefaults.MAX_SIZE, PaginationDefaults.clamp(5_000));
        assertEquals(PaginationDefaults.MAX_SIZE, PaginationDefaults.clamp(101));
    }

    @Test
    void clamp_passesThroughInRange() {
        assertEquals(50, PaginationDefaults.clamp(50));
        assertEquals(1, PaginationDefaults.clamp(1));
    }

    @Test
    void clamp_collapsesNonPositiveToDefault() {
        assertEquals(PaginationDefaults.DEFAULT_SIZE, PaginationDefaults.clamp(0));
        assertEquals(PaginationDefaults.DEFAULT_SIZE, PaginationDefaults.clamp(-1));
    }

    @Test
    void clampExport_raisesCeilingToExportMaxSize() {
        // BUG-010 — the bulk-export path lifts the UI's 100 cap to 1000
        // for a one-off CSV dump. Without this, exporting 34k orders
        // took ~340 serial requests at size=100 each.
        assertEquals(1000, PaginationDefaults.EXPORT_MAX_SIZE);
        assertEquals(1000, PaginationDefaults.clampExport(5_000));
        assertEquals(1000, PaginationDefaults.clampExport(1001));
    }

    @Test
    void clampExport_passesThroughInRange() {
        assertEquals(500, PaginationDefaults.clampExport(500));
        assertEquals(100, PaginationDefaults.clampExport(100));
        assertEquals(1, PaginationDefaults.clampExport(1));
    }

    @Test
    void clampExport_collapsesNonPositiveToDefault() {
        assertEquals(PaginationDefaults.DEFAULT_SIZE, PaginationDefaults.clampExport(0));
        assertEquals(PaginationDefaults.DEFAULT_SIZE, PaginationDefaults.clampExport(-1));
    }
}
