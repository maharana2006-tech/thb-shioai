package com.multiship.backend.service;

import com.multiship.backend.model.Order;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * BUG-052 — the Billing-statement renderer sourced CLIENT from
 * {@code order.custNo}; modern orders only carry {@code tenantId},
 * so the row showed "—". These pin the prefer-tenantId, fall-back-
 * to-custNo contract used by both {@code list()} and {@code toRow()}.
 */
class OrderDocumentSummaryClientLabelTest {

    @Test
    void prefersTenantIdWhenPresent() {
        Order o = new Order();
        o.setTenantId("ACME");
        o.setCustNo("legacy-code");
        assertEquals("ACME", OrderDocumentSummaryService.clientLabel(o));
    }

    @Test
    void fallsBackToCustNoWhenTenantIdIsNull() {
        Order o = new Order();
        o.setTenantId(null);
        o.setCustNo("MKL246");
        assertEquals("MKL246", OrderDocumentSummaryService.clientLabel(o));
    }

    @Test
    void fallsBackToCustNoWhenTenantIdIsBlank() {
        // Some seeders set "" rather than null; both must behave the same.
        Order o = new Order();
        o.setTenantId("   ");
        o.setCustNo("MKL246");
        assertEquals("MKL246", OrderDocumentSummaryService.clientLabel(o));
    }

    @Test
    void bothBlankReturnsNull() {
        Order o = new Order();
        o.setTenantId(null);
        o.setCustNo("  ");
        assertNull(OrderDocumentSummaryService.clientLabel(o));
    }

    @Test
    void trimsWhitespace() {
        Order o = new Order();
        o.setTenantId("  ACME  ");
        assertEquals("ACME", OrderDocumentSummaryService.clientLabel(o));
    }

    @Test
    void nullOrderReturnsNull() {
        assertNull(OrderDocumentSummaryService.clientLabel(null));
    }
}
