package com.multiship.backend.service;

import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.InvoiceCopies;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.InvoiceCopiesRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

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
public class InvoiceCopiesService {

    /** Hard fallback when no rule exists. Historical behaviour was always 1. */
    public static final int DEFAULT_COPIES = 1;
    /** DB CHECK enforces 1..20. Service double-guards with a clearer error. */
    static final int MIN_COPIES = 1;
    static final int MAX_COPIES = 20;

    private final InvoiceCopiesRepository repository;
    /** PR-Printer-R7c — nullable so the R7a service still constructs cleanly
     *  in unit tests that don't touch the order-resolution path. */
    private final OrderTrackingRepository orderTrackingRepository;
    private final CarrierAccountRefRepository carrierAccountRefRepository;

    @Autowired
    public InvoiceCopiesService(InvoiceCopiesRepository repository,
                                OrderTrackingRepository orderTrackingRepository,
                                CarrierAccountRefRepository carrierAccountRefRepository) {
        this.repository = repository;
        this.orderTrackingRepository = orderTrackingRepository;
        this.carrierAccountRefRepository = carrierAccountRefRepository;
    }

    /** R7a-era constructor kept for backwards-compat with tests that don't
     *  exercise the R7c resolveCopiesForOrder path. Spring uses the
     *  @Autowired 3-arg constructor for production wiring; this one is only
     *  reachable from unit tests + is why the 3-arg one carries @Autowired
     *  (per [[component-ctor-overload-needs-autowired]]). */
    InvoiceCopiesService(InvoiceCopiesRepository repository) {
        this(repository, null, null);
    }

    /**
     * PR-Printer-R7c — the integration point for the invoice-print
     * dispatch site. Looks up the order's OrderTracking, resolves the
     * carrier via CarrierAccountRef, and returns copies per the R7a
     * fallback chain. Never throws — a missing tracking / account
     * / carrier just falls through to {@link #DEFAULT_COPIES}.
     */
    public int resolveCopiesForOrder(Integer orderNo) {
        if (orderNo == null || orderTrackingRepository == null || carrierAccountRefRepository == null) {
            return DEFAULT_COPIES;
        }
        try {
            Optional<OrderTracking> tracking = orderTrackingRepository.findByOrderNo(orderNo);
            if (tracking.isEmpty()) return DEFAULT_COPIES;
            String accountNumber = tracking.get().getAccountNumber();
            if (!StringUtils.hasText(accountNumber)) return DEFAULT_COPIES;
            Optional<CarrierAccountRef> account = carrierAccountRefRepository
                    .findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc(accountNumber);
            if (account.isEmpty()) return DEFAULT_COPIES;
            String carrierCode = account.get().getCarrierCode();
            String clientCode = account.get().getCustomerNo();
            return resolveCopies(clientCode, carrierCode);
        } catch (Exception e) {
            // Any lookup blip: fall back to the historical behaviour.
            // Never let a copies-config lookup fail an invoice-print job.
            log.warn("resolveCopiesForOrder({}) failed, defaulting to 1: {}", orderNo, e.toString());
            return DEFAULT_COPIES;
        }
    }

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
