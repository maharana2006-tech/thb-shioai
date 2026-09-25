package com.multiship.backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The store's value rules; its SQL runs against Postgres (the backfill, the batch page). */
class ImportBatchRowStoreTest {

    /** 2 and 2.00 are one weight: a re-save must not rewrite every row whose number came back rescaled. */
    @Test
    void numbersCompareByValueSoAnUnchangedRowIsNotRewritten() {
        assertTrue(ImportBatchRowStore.same(new BigDecimal("2"), new BigDecimal("2.00")));
        assertFalse(ImportBatchRowStore.same(new BigDecimal("2"), new BigDecimal("2.01")));
        assertTrue(ImportBatchRowStore.same(null, null));
        assertFalse(ImportBatchRowStore.same("a", null));
    }

    /** Errors are a JSON array; an old WMS row stored "a, b" — shown as it was, not lost. */
    @Test
    void errorsReadBackFromJsonAndFromTheOldPlainText() {
        assertEquals(List.of("weight must be > 0", "state 'ZZ' is not valid"),
                ImportBatchRowStore.readList("[\"weight must be > 0\",\"state 'ZZ' is not valid\"]"));
        assertEquals(List.of("weight must be > 0, city is required"), ImportBatchRowStore.readList("weight must be > 0, city is required"));
        assertTrue(ImportBatchRowStore.readList(null).isEmpty());
    }
}
