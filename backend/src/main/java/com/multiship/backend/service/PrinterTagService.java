package com.multiship.backend.service;

import com.multiship.backend.model.PrinterTag;
import com.multiship.backend.repository.PrinterTagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PR-Printer-R8a — free-form tags per printer.
 *
 * <p>Storage is always lowercased; {@link #normaliseTag(String)}
 * enforces the invariant and rejects blanks / too-long values before
 * the DB CHECK catches them.
 *
 * <p>{@link #replaceAllForPrinter(Long, Collection)} is the primary
 * mutation surface — the FE editor sends the full desired set on save,
 * the service computes the diff and applies delete + insert atomically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrinterTagService {

    static final int MAX_TAG_LENGTH = 60;

    private final PrinterTagRepository repository;

    public List<PrinterTag> listForPrinter(Long printerId) {
        if (printerId == null) return List.of();
        return repository.findByPrinterIdOrderByTagAsc(printerId);
    }

    /** Feeds the FE table's chip column. Grouped by printerId for O(1) lookup. */
    public Map<Long, List<String>> tagsByPrinterId(Collection<Long> printerIds) {
        if (printerIds == null || printerIds.isEmpty()) return Map.of();
        return repository.findByPrinterIdInOrderByPrinterIdAscTagAsc(printerIds).stream()
                .collect(Collectors.groupingBy(
                        PrinterTag::getPrinterId,
                        Collectors.mapping(PrinterTag::getTag, Collectors.toList())));
    }

    /** Autocomplete: distinct-tag list across every printer. */
    public List<String> distinctTags() {
        return repository.findDistinctTags();
    }

    /**
     * Replace-all: FE sends the full desired tag set; service applies
     * the diff. Rejects invalid tags before touching the DB.
     * @return the final persisted list, sorted alphabetically.
     */
    @Transactional
    public List<PrinterTag> replaceAllForPrinter(Long printerId, Collection<String> desired) {
        if (printerId == null) {
            throw new IllegalArgumentException("printerId is required.");
        }
        // Normalise + dedupe input in one pass. LinkedHashSet keeps
        // deterministic order for error messages.
        LinkedHashSet<String> next = new LinkedHashSet<>();
        if (desired != null) {
            for (String raw : desired) {
                String norm = normaliseTag(raw);
                if (norm != null) next.add(norm);
            }
        }
        // Current row snapshot.
        List<PrinterTag> current = repository.findByPrinterIdOrderByTagAsc(printerId);
        var currentTags = current.stream().map(PrinterTag::getTag).collect(Collectors.toSet());

        // Delete rows whose tag is no longer desired.
        List<PrinterTag> toDelete = new ArrayList<>();
        for (PrinterTag row : current) {
            if (!next.contains(row.getTag())) toDelete.add(row);
        }
        if (!toDelete.isEmpty()) repository.deleteAll(toDelete);

        // Insert new rows.
        List<PrinterTag> toInsert = new ArrayList<>();
        for (String tag : next) {
            if (!currentTags.contains(tag)) {
                PrinterTag row = new PrinterTag();
                row.setPrinterId(printerId);
                row.setTag(tag);
                toInsert.add(row);
            }
        }
        if (!toInsert.isEmpty()) repository.saveAll(toInsert);

        log.info("printer_tags replace: printer={} removed={} added={} final={}",
                printerId, toDelete.size(), toInsert.size(), next.size());
        return repository.findByPrinterIdOrderByTagAsc(printerId);
    }

    /** Cascade helper: called on printer delete to clean up the M2M rows. */
    @Transactional
    public void deleteAllForPrinter(Long printerId) {
        if (printerId == null) return;
        repository.deleteByPrinterId(printerId);
    }

    /**
     * Normalise a raw tag input. Trims, lowercases, rejects blanks,
     * caps at {@link #MAX_TAG_LENGTH} characters.
     * @return the normalised tag, or {@code null} if the input was blank.
     * @throws IllegalArgumentException if too long.
     */
    static String normaliseTag(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() > MAX_TAG_LENGTH) {
            throw new IllegalArgumentException(
                    "Tag \"" + raw + "\" is longer than " + MAX_TAG_LENGTH + " characters.");
        }
        return trimmed;
    }
}
