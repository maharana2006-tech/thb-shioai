package com.multiship.backend.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-076 regression for the audit-log CSV export. The /export endpoint emits
 * tenant-authored notes / entityKey / changes across all tenants to an admin,
 * so a cell starting with a formula sentinel must be neutralized.
 */
class AuditLogControllerCsvTest {

    @Test
    void csvNeutralizesLeadingFormulaChar() {
        assertEquals("'=cmd|'/c calc'!A1", AuditLogController.csv("=cmd|'/c calc'!A1"));
        assertEquals("'@SUM(A1)", AuditLogController.csv("@SUM(A1)"));
    }

    @Test
    void csvNullAndBenignUnchanged() {
        assertEquals("", AuditLogController.csv(null));
        assertEquals("plain note", AuditLogController.csv("plain note"));
    }
}
