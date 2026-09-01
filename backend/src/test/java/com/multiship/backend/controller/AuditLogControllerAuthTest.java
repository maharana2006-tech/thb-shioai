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
