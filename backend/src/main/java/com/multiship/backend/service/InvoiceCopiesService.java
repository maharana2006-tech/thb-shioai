package com.multiship.backend.service;

import com.multiship.backend.model.InvoiceCopies;
import com.multiship.backend.repository.InvoiceCopiesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * PR-Printer-R7a — resolve, list, upsert, delete the per (client,
 * carrier) commercial-invoice copies rules. See V70 + model javadoc.
 *
 * <p>Fallback chain in {@link #resolveCopies}:
 * <ol>
 *   <li>Exact match on (clientCode, carrierCode) → use that copies value.</li>
 *   <li>Tenant-default match on (NULL, carrierCode) → use that.</li>
 *   <li>Nothing → return 1 (hardcoded fallback, matches pre-R7 behaviour).</li>
 * </ol>
 *
 * <p>The print-dispatch site consults {@link #resolveCopies} when
 * emitting an INVOICE job (wiring lands in R7c).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceCopiesService {

    /** Hard fallback when no rule exists. Historical behaviour was always 1. */
    public static final int DEFAULT_COPIES = 1;
    /** DB CHECK enforces 1..20. Service double-guards with a clearer error. */
    static final int MIN_COPIES = 1;
    static final int MAX_COPIES = 20;

    private final InvoiceCopiesRepository repository;

    /**
     * The main integration point for the print-dispatch site.
     * Never throws — always returns at least {@link #DEFAULT_COPIES}.
     */
    public int resolveCopies(String clientCode, String carrierCode) {
        if (!StringUtils.hasText(carrierCode)) return DEFAULT_COPIES;
        // Step 1: exact per-client match.
        if (StringUtils.hasText(clientCode)) {
            var exact = repository.findRule(clientCode, carrierCode);
            if (exact.isPresent()) return exact.get().getCopies();
        }
        // Step 2: tenant-wide default row.
        var wildcard = repository.findRule(null, carrierCode);
        if (wildcard.isPresent()) return wildcard.get().getCopies();
        // Step 3: nothing configured.
        return DEFAULT_COPIES;
    }

    /** Admin surface: list every row for the FE Copies-tab matrix. */
    public List<InvoiceCopies> listAll() {
        return repository.findAllOrdered();
    }

    /**
     * Upsert a (client, carrier) rule. {@code clientCode == null} sets the
     * tenant-wide default row for that carrier. Bounded 1..20; a value
     * outside that throws {@link IllegalArgumentException}.
     */
    @Transactional
    public InvoiceCopies upsert(String clientCode, String carrierCode, int copies) {
        if (!StringUtils.hasText(carrierCode)) {
            throw new IllegalArgumentException("carrierCode is required.");
        }
        if (copies < MIN_COPIES || copies > MAX_COPIES) {
            throw new IllegalArgumentException(
                    "copies must be between " + MIN_COPIES + " and " + MAX_COPIES + ".");
        }
        String normalClient = StringUtils.hasText(clientCode) ? clientCode.trim().toUpperCase() : null;
        String normalCarrier = carrierCode.trim().toUpperCase();
        InvoiceCopies row = repository.findRule(normalClient, normalCarrier).orElseGet(InvoiceCopies::new);
        row.setClientCode(normalClient);
        row.setCarrierCode(normalCarrier);
        row.setCopies(copies);
        InvoiceCopies saved = repository.save(row);
        log.info("invoice_copies upserted: client={} carrier={} copies={}",
                normalClient, normalCarrier, copies);
        return saved;
    }

    /**
     * Delete a specific (client, carrier) rule. Idempotent — missing row
     * is a no-op. Deleting a tenant-default row makes the carrier fall
     * back to {@link #DEFAULT_COPIES}.
     */
    @Transactional
    public boolean delete(String clientCode, String carrierCode) {
        String normalClient = StringUtils.hasText(clientCode) ? clientCode.trim().toUpperCase() : null;
        String normalCarrier = StringUtils.hasText(carrierCode) ? carrierCode.trim().toUpperCase() : null;
        if (normalCarrier == null) return false;
        var found = repository.findRule(normalClient, normalCarrier);
        if (found.isEmpty()) return false;
        repository.delete(found.get());
        log.info("invoice_copies deleted: client={} carrier={}", normalClient, normalCarrier);
        return true;
    }
}
