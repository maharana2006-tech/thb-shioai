package com.multiship.backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sprint 51 BS-M3 (interim) — verifies the audit-log list endpoint carries the
 * ADMIN-only {@code @PreAuthorize} annotation. Method-level Spring Security
 * expressions are the wire-level gate; enforcing them here keeps the fix from
 * silently regressing to {@code hasAnyRole('ADMIN','USER')} which previously
 * let USER-tenants enumerate cross-tenant audit rows via the actor/entityKey
 * filters. Full fix (per-row client_code column + scope filter) is deferred.
 */
class AuditLogControllerAuthTest {

    @Test
    void listEndpoint_isAdminOnly() throws Exception {
        Method list = AuditLogController.class.getDeclaredMethod("list",
                String.class, String.class, String.class, String.class,
                String.class, String.class, int.class, int.class);
        PreAuthorize gate = list.getAnnotation(PreAuthorize.class);
        assertNotNull(gate, "list() must carry @PreAuthorize");
        assertEquals("hasRole('ADMIN')", gate.value(),
                "BS-M3 interim fix requires hasRole('ADMIN'); a USER-tenant "
                        + "must not be able to reach cross-tenant audit rows.");
    }
}
