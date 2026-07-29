package com.multiship.backend.service.dg;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@link UnNumberDirectory} — the curated UN number dataset.
 * Mirrors the Sprint 8 HsCodeDirectoryTest pattern.
 */
class UnNumberDirectoryTest {

    private UnNumberDirectory directory;

    @BeforeEach
    void setUp() {
        directory = new UnNumberDirectory(new ObjectMapper());
        directory.load();
    }

    @Test
    void datasetLoadsSuccessfullyAndIsNonEmpty() {
        List<UnNumberEntry> all = directory.all();
        assertFalse(all.isEmpty(),
                "Curated UN number dataset should load with at least one entry");
    }

    @Test
    void lookupByExactUnNumberFindsLithiumIonBatteries() {
        Optional<UnNumberEntry> found = directory.byNumber("UN3480");
        assertTrue(found.isPresent());
        assertEquals("Lithium ion batteries", found.get().properShippingName());
        assertEquals("9", found.get().hazardClass());
    }

    @Test
    void lookupByExactUnNumberIsCaseInsensitive() {
        assertTrue(directory.byNumber("un3480").isPresent());
        assertTrue(directory.byNumber(" UN3480 ").isPresent());
    }

    @Test
    void unknownUnNumberReturnsEmpty() {
        assertTrue(directory.byNumber("UN9999").isEmpty());
    }

    @Test
    void nullOrBlankQueryReturnsEmpty() {
        assertTrue(directory.byNumber(null).isEmpty());
        assertTrue(directory.search(null).isEmpty());
        assertTrue(directory.search("").isEmpty());
    }

    @Test
    void searchByDigitPrefixMatchesUnNumbers() {
        // "348" should match UN3480 (Li-ion) and UN3481 (Li-ion in equipment).
        List<UnNumberEntry> hits = directory.search("348");
        assertTrue(hits.size() >= 2,
                "Expected at least 2 hits for '348', got " + hits.size());
        assertTrue(hits.stream().anyMatch(e -> "UN3480".equalsIgnoreCase(e.unNumber())));
        assertTrue(hits.stream().anyMatch(e -> "UN3481".equalsIgnoreCase(e.unNumber())));
    }

    @Test
    void searchByShippingNameSubstringMatches() {
        List<UnNumberEntry> hits = directory.search("aerosol");
        assertTrue(hits.stream().anyMatch(e ->
                e.properShippingName().equalsIgnoreCase("Aerosols")));
    }

    @Test
    void searchIsCaseInsensitive() {
        assertNotNull(directory.search("LITHIUM"));
        assertTrue(directory.search("LITHIUM").size() > 0);
        assertTrue(directory.search("lithium").size() > 0);
    }

    @Test
    void searchResultsCappedAt25() {
        // Overly broad query — every entry has "s" in its shipping name.
        List<UnNumberEntry> hits = directory.search("s");
        assertTrue(hits.size() <= 25,
                "Search should cap at 25 hits, got " + hits.size());
    }

    @Test
    void entriesAreSortedByNumericUnValue() {
        List<UnNumberEntry> all = directory.all();
        for (int i = 1; i < all.size(); i++) {
            String prev = all.get(i - 1).unNumber().replaceAll("[^0-9]", "");
            String cur = all.get(i).unNumber().replaceAll("[^0-9]", "");
            assertTrue(prev.compareTo(cur) <= 0,
                    "Entries should be sorted by numeric UN; " + prev + " > " + cur);
        }
    }
}
