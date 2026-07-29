package com.multiship.backend.service.hs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the actual production dataset so a bad JSON edit fails a test
 * rather than silently returning empty results at runtime. Every assertion
 * is against the real curated entries.
 */
class HsCodeDirectoryTest {

    private HsCodeDirectory directory;

    @BeforeEach
    void setUp() {
        directory = new HsCodeDirectory(new ObjectMapper());
        directory.load(); // normally @PostConstruct
    }

    @Test
    void datasetLoads() {
        assertFalse(directory.all().isEmpty(), "common.json should load at least one entry");
        assertTrue(directory.all().size() >= 30,
                "Curated set should have at least 30 entries — got " + directory.all().size());
    }

    @Test
    void searchByCodePrefix() {
        List<HsCodeEntry> shirts = directory.search("6109");
        assertFalse(shirts.isEmpty(), "6109 should match at least one T-shirt category entry");
        assertTrue(shirts.stream().allMatch(e -> e.code().startsWith("6109")),
                "Every match should share the queried code prefix");
    }

    @Test
    void searchByDescriptionSubstringCaseInsensitive() {
        List<HsCodeEntry> tshirts = directory.search("T-shirt");
        assertFalse(tshirts.isEmpty(), "'T-shirt' should match description entries");
        List<HsCodeEntry> lowered = directory.search("t-shirt");
        assertEquals(tshirts.size(), lowered.size(),
                "Search should be case-insensitive");
    }

    @Test
    void searchByCategory() {
        List<HsCodeEntry> apparel = directory.search("Apparel");
        assertFalse(apparel.isEmpty(), "Apparel category should return the apparel entries");
    }

    @Test
    void searchResultsCappedAtMaxResults() {
        // Every entry has at least one lowercase letter — search "a" should
        // hit many but be capped at 25.
        List<HsCodeEntry> many = directory.search("a");
        assertTrue(many.size() <= 25, "Search results should be capped at 25");
    }

    @Test
    void byCodeReturnsEntry() {
        // 610910 is in the dataset (T-shirts, cotton knitted)
        assertNotNull(directory.byCode("610910").orElse(null));
        assertNotNull(directory.byCode("6109.10").orElse(null),
                "Dot-separated codes should resolve to the same entry");
    }

    @Test
    void byCodeReturnsEmptyForUnknownCode() {
        assertTrue(directory.byCode("999999").isEmpty(),
                "Unknown codes return empty — not an error");
    }

    @Test
    void byCodeToleratesFormatting() {
        // Dots, spaces, hyphens all stripped before comparison
        assertNotNull(directory.byCode("6109.10").orElse(null));
        assertNotNull(directory.byCode("6109 10").orElse(null));
        assertNotNull(directory.byCode("6109-10").orElse(null));
    }

    @Test
    void searchWithNullOrBlankReturnsEmpty() {
        assertTrue(directory.search(null).isEmpty());
        assertTrue(directory.search("").isEmpty());
        assertTrue(directory.search("   ").isEmpty());
    }

    @Test
    void everyEntryHasCodeAndDescription() {
        for (HsCodeEntry e : directory.all()) {
            assertNotNull(e.code(), "Entry missing code");
            assertNotNull(e.description(), "Entry " + e.code() + " missing description");
            assertTrue(e.code().length() >= 6 && e.code().length() <= 10,
                    "Entry " + e.code() + " should be 6-10 digits");
        }
    }
}
