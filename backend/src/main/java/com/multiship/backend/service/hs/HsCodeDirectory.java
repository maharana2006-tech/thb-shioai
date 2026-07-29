package com.multiship.backend.service.hs;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Autocomplete-backed HS code directory. Loads a hand-curated common-codes
 * dataset ({@code hs-codes/common.json} on the classpath) at startup and
 * serves prefix + substring searches from memory. Not authoritative — the
 * dataset is a starting point for common ecommerce categories; operators
 * can still type any 6-10 digit code the {@link
 * com.multiship.backend.service.IntlShipmentValidator} accepts.
 *
 * <p>Kept behind a service (rather than a repository) because the dataset
 * lives in resources, not the DB — swapping to a proper WCO / tariff feed
 * later is a service-layer change, not a schema change.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HsCodeDirectory {

    private static final int MAX_RESULTS = 25;
    private static final String RESOURCE_PATH = "hs-codes/common.json";

    private final ObjectMapper objectMapper;

    private List<HsCodeEntry> entries = List.of();

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            HsCodeEntry[] parsed = objectMapper.readValue(in, HsCodeEntry[].class);
            entries = Arrays.stream(parsed)
                    // Sort once so results come back in a stable order
                    // (by category, then description) — helps the UI show
                    // related codes next to each other.
                    .sorted(Comparator
                            .comparing((HsCodeEntry e) -> e.category() == null ? "" : e.category())
                            .thenComparing(HsCodeEntry::description))
                    .toList();
            log.info("Loaded {} HS codes from {}.", entries.size(), RESOURCE_PATH);
        } catch (Exception ex) {
            log.warn("Failed to load HS code directory from {}: {}. Autocomplete will be empty.",
                    RESOURCE_PATH, ex.getMessage());
            entries = List.of();
        }
    }

    /**
     * Look up an exact code (digits only, dot / space / hyphen tolerant).
     * Returns empty when the code isn't in the curated set — not an error;
     * operators can always type free-form.
     */
    public Optional<HsCodeEntry> byCode(String code) {
        if (code == null) return Optional.empty();
        String normalized = normalize(code);
        if (normalized.isEmpty()) return Optional.empty();
        return entries.stream()
                .filter(e -> normalized.equals(normalize(e.code())))
                .findFirst();
    }

    /**
     * Fuzzy search — code prefix OR description substring, case-insensitive.
     * Capped at {@link #MAX_RESULTS} entries so a stray one-letter query
     * doesn't return the whole dataset.
     */
    public List<HsCodeEntry> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.trim().toLowerCase(Locale.ROOT);
        String qDigits = normalize(query);
        return entries.stream()
                .filter(e -> matches(e, q, qDigits))
                .limit(MAX_RESULTS)
                .toList();
    }

    private static boolean matches(HsCodeEntry e, String qLower, String qDigits) {
        // Prefer digits-only code match when the query looks like digits.
        if (!qDigits.isEmpty() && normalize(e.code()).startsWith(qDigits)) return true;
        // Otherwise fall back to description / category substring.
        return e.description().toLowerCase(Locale.ROOT).contains(qLower)
                || (e.category() != null && e.category().toLowerCase(Locale.ROOT).contains(qLower));
    }

    /** Strip formatting separators from an HS code so comparisons ignore them. */
    private static String normalize(String code) {
        if (code == null) return "";
        return code.trim().replaceAll("[.\\s\\-]", "");
    }

    /** All entries — used by tests and by the {@code /categories} endpoint if we add one. */
    public List<HsCodeEntry> all() {
        return entries;
    }
}
