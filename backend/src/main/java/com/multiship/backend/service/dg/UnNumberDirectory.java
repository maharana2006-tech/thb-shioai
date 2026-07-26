package com.multiship.backend.service.dg;

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
 * Curated UN number directory — mirrors the Sprint 8
 * {@code HsCodeDirectory} pattern. Loads {@code un-numbers/common.json}
 * from the classpath at startup and serves lookups + fuzzy search from
 * memory. Not authoritative — the dataset covers the ~25 most common
 * commercial DG shipments (lithium batteries, aerosols, small quantities
 * of common flammables). Operators can still type any {@code UN\d{4}}
 * the validator accepts.
 *
 * <p>Kept behind a service because the dataset lives in resources —
 * swapping to a proper Table A feed later is a service-layer change,
 * not a schema change.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnNumberDirectory {

    private static final int MAX_RESULTS = 25;
    private static final String RESOURCE_PATH = "un-numbers/common.json";

    private final ObjectMapper objectMapper;

    private List<UnNumberEntry> entries = List.of();

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            UnNumberEntry[] parsed = objectMapper.readValue(in, UnNumberEntry[].class);
            entries = Arrays.stream(parsed)
                    // Stable sort by numeric UN so autocomplete shows related
                    // groups adjacent (e.g. UN3090..UN3091 together).
                    .sorted(Comparator.comparing(UnNumberDirectory::numericPart))
                    .toList();
            log.info("Loaded {} UN numbers from {}.", entries.size(), RESOURCE_PATH);
        } catch (Exception ex) {
            log.warn("Failed to load UN number directory from {}: {}. Autocomplete will be empty.",
                    RESOURCE_PATH, ex.getMessage());
            entries = List.of();
        }
    }

    /**
     * Exact lookup by UN number. Case-insensitive on the prefix.
     * Returns empty when the number isn't in the curated set — not an
     * error; operators can always type free-form.
     */
    public Optional<UnNumberEntry> byNumber(String unNumber) {
        if (unNumber == null) return Optional.empty();
        String normalised = unNumber.trim().toUpperCase(Locale.ROOT);
        return entries.stream()
                .filter(e -> normalised.equals(e.unNumber().toUpperCase(Locale.ROOT)))
                .findFirst();
    }

    /**
     * Fuzzy search: UN number prefix OR proper-shipping-name substring,
     * case-insensitive. Capped at {@link #MAX_RESULTS} so a one-letter
     * query doesn't return the whole dataset.
     */
    public List<UnNumberEntry> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.trim().toLowerCase(Locale.ROOT);
        String qNum = q.replaceAll("[^0-9]", "");
        return entries.stream()
                .filter(e -> matches(e, q, qNum))
                .limit(MAX_RESULTS)
                .toList();
    }

    private static boolean matches(UnNumberEntry e, String qLower, String qDigits) {
        if (!qDigits.isEmpty() && numericPart(e).startsWith(qDigits)) return true;
        return e.properShippingName().toLowerCase(Locale.ROOT).contains(qLower)
                || (e.notes() != null && e.notes().toLowerCase(Locale.ROOT).contains(qLower));
    }

    /** Extract the numeric portion of a UN number for sorting / prefix search. */
    private static String numericPart(UnNumberEntry e) {
        if (e.unNumber() == null) return "";
        return e.unNumber().replaceAll("[^0-9]", "");
    }

    /** All entries — used by tests. */
    public List<UnNumberEntry> all() {
        return entries;
    }
}
