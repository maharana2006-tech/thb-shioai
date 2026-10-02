package com.multiship.backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sprint 51 follow-up BS-M3 (full fix) — verifies the audit-log list endpoint
 * carries the ADMIN+USER {@code @PreAuthorize}. The interim ADMIN-only gate
 * (added by Sprint 51 BS-M3) is now redundant: a persisted {@code client_code}
 * column plus repository-layer scope predicate mean a tenant-scoped USER can
 * only ever see their own tenant's rows. Enforcing the annotation here keeps
 * the fix from regressing to a stricter or looser expression.
 */
class AuditLogControllerAuthTest {

    @Test
    void exportEndpoint_isAdminOnly() throws Exception {
        // Audit B6 (#357) — list opens to USER (tenant-scoped at the repo),
        // but the CSV export is a much bigger blast radius: 100k rows in
        // one hit, including the full change blob. Pin ADMIN-only so a
        // code refactor can't quietly widen it.
        Method export = AuditLogController.class.getDeclaredMethod("export",
                String.class, String.class, String.class, String.class,
                String.class, Integer.class, String.class, String.class,
                jakarta.servlet.http.HttpServletResponse.class);
        PreAuthorize gate = export.getAnnotation(PreAuthorize.class);
        assertNotNull(gate, "export() must carry @PreAuthorize");
        assertEquals("hasRole('ADMIN')", gate.value(),
                "CSV export is ADMIN-only — list is open to USER but export isn't.");
    }

    @Test
    void csvQuoting_handlesDelimitersQuotesAndNewlines() throws Exception {
        // Audit B6 (#357) — RFC 4180 quoting on the CSV path. The changes
        // blob in the audit log is JSON — commas + embedded quotes are
        // routine and would silently corrupt the output without this.
        Method csv = AuditLogController.class.getDeclaredMethod("csv", Object.class);
        csv.setAccessible(true);
        assertEquals("", csv.invoke(null, (Object) null));
        assertEquals("plain", csv.invoke(null, "plain"));
        assertEquals("\"with,comma\"", csv.invoke(null, "with,comma"));
        assertEquals("\"with\"\"quote\"", csv.invoke(null, "with\"quote"));
        assertEquals("\"two\nlines\"", csv.invoke(null, "two\nlines"));
    }

    @Test
    void listEndpoint_allowsAdminAndUser() throws Exception {
        // Signature growth history:
        //   original — 6 Strings + 2 ints
        //   Audit A3 — added `sort` String → 7 Strings + 2 ints
        //   feat(logs) b2f69c4 — added `category` String + `orderNo` Integer
        //                        (between entityKey and since) → 8 Strings +
        //                        1 Integer + 2 ints (11 params total)
        Method list = AuditLogController.class.getDeclaredMethod("list",
                String.class, String.class, String.class, String.class,
                String.class, Integer.class, String.class, String.class,
                String.class, int.class, int.class);
        PreAuthorize gate = list.getAnnotation(PreAuthorize.class);
        assertNotNull(gate, "list() must carry @PreAuthorize");
        assertEquals("hasAnyRole('ADMIN','USER')", gate.value(),
                "BS-M3 full fix opens the endpoint to USER; scope filtering happens "
                        + "at the repository via the persisted client_code column.");
    }
}
