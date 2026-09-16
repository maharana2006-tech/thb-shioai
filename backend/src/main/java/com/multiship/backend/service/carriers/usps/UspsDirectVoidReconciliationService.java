package com.multiship.backend.service.carriers.usps;

import com.multiship.backend.dto.UspsVoidReconciliationSummaryDTO;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.OrderTrackingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * PR-D — reconciles optimistic USPS_DIRECT voids against USPS's
 * asynchronous eVS Refund report.
 *
 * <p>Because USPS APIs v3 have no synchronous void endpoint (documented
 * gotcha #9 in {@code docs/usps-direct-integration.md}), an operator's
 * click on "Void" flips {@link OrderTracking#getStatus()} to
 * {@code VOIDED} immediately (optimistic UX; see decision #19). USPS
 * only confirms or refuses the refund via its eVS Refund report, which
 * the platform admin uploads to
 * {@code POST /api/v1/admin/usps-direct/void-reconciliation/run} for
 * this service to process.
 *
 * <p><b>eVS Refund report CSV shape.</b> USPS publishes the report as a
 * comma-separated file with a header row; the columns this service
 * consumes are:
 * <pre>
 *   TrackingNumber, RefundStatus, RefundDate, RefundAmount
 * </pre>
 * See {@link #EXPECTED_HEADERS} for the exact ordering. Case is
 * normalized during parsing so a report with lower-case or mixed-case
 * headers still reconciles. Rows with additional trailing columns are
 * ignored — USPS occasionally adds diagnostic fields between annual
 * releases and we don't want to fail-closed on those.
 *
 * <p>Per-row transitions (assumes the local status is currently
 * {@code VOIDED}):
 * <ul>
 *   <li>{@code APPROVED} → mark
 *       {@code void_reconciliation_status = RECONCILED_APPROVED};
 *       {@code status} stays {@code VOIDED}. Happy path.</li>
 *   <li>{@code DENIED} → flip {@code status = VOID_FAILED},
 *       mark {@code void_reconciliation_status = RECONCILED_DENIED};
 *       WARN log so ops see the toast candidate (event bus wiring is a
 *       follow-up; see the FE non-goal in PR-D scope).</li>
 *   <li>{@code PENDING} → leave everything untouched; the row will be
 *       retried against the next report.</li>
 * </ul>
 *
 * <p>Idempotency: running the same CSV twice is a no-op on the second
 * pass because the reconciliation query in
 * {@link OrderTrackingRepository#findVoidedUnreconciledUspsBetween}
 * excludes rows that already have {@code void_reconciliation_status}
 * populated. This service reinforces the guarantee by SHORT-CIRCUITING
 * rows whose {@code voidReconciliationStatus} is already terminal
 * (APPROVED / DENIED) even if the caller passes a CSV that includes
 * them; the row is counted as {@code skipped} with a note.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UspsDirectVoidReconciliationService {

    private final OrderTrackingRepository orderTrackingRepository;

    /**
     * Header row this service expects. Order-independent — the parser
     * reads column positions from the actual header line and looks up
     * fields by name. Missing required columns fail-closed with a
     * clear error message.
     */
    public static final List<String> EXPECTED_HEADERS = List.of(
            "TrackingNumber", "RefundStatus", "RefundDate", "RefundAmount");

    /** Required subset — the reconciler needs these two even if the
     *  report drops the optional columns. */
    private static final List<String> REQUIRED_HEADERS = List.of(
            "TrackingNumber", "RefundStatus");

    // Reconciliation-status enum values, exposed as public constants so
    // tests and controllers can reference them without magic strings.
    public static final String RECONCILED_APPROVED = "RECONCILED_APPROVED";
    public static final String RECONCILED_DENIED = "RECONCILED_DENIED";

    // Local OrderTracking.status values written by this service.
    public static final String STATUS_VOIDED = "VOIDED";
    public static final String STATUS_VOID_FAILED = "VOID_FAILED";

    /**
     * Parse the uploaded eVS Refund report and apply the per-row
     * transitions described in the class javadoc. Returns a summary
     * suitable for direct return from the admin controller.
     *
     * @param csv byte stream of the CSV file (UTF-8 assumed). Never
     *            null; blank / empty stream returns
     *            {@link UspsVoidReconciliationSummaryDTO#empty()}.
     */
    @Transactional
    public UspsVoidReconciliationSummaryDTO reconcile(InputStream csv) {
        if (csv == null) {
            return UspsVoidReconciliationSummaryDTO.empty();
        }
        List<String> errors = new ArrayList<>();
        int processed = 0;
        int approved = 0;
        int denied = 0;
        int pending = 0;
        int skipped = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                return UspsVoidReconciliationSummaryDTO.empty();
            }
            Map<String, Integer> headerIndex;
            try {
                headerIndex = parseHeader(headerLine);
            } catch (IllegalArgumentException ex) {
                // Malformed header — fail-closed with a single error entry
                // rather than silently reconciling nothing. Controller
                // maps this to a 400.
                throw ex;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                processed++;
                try {
                    Outcome outcome = reconcileRow(line, headerIndex);
                    switch (outcome) {
                        case APPROVED -> approved++;
                        case DENIED -> denied++;
                        case PENDING -> pending++;
                        case SKIPPED_UNKNOWN, SKIPPED_ALREADY, SKIPPED_NOT_VOIDED -> skipped++;
                        case MALFORMED -> errors.add("Row " + processed + ": malformed / missing required columns.");
                    }
                } catch (Exception ex) {
                    // Never abort mid-file — one bad row shouldn't spoil
                    // the whole batch. Log at DEBUG (WARN when the caller
                    // reads the summary) and continue.
                    log.debug("USPS void reconciliation row {} failed: {}", processed, ex.getMessage());
                    errors.add("Row " + processed + ": " + ex.getMessage());
                }
            }
        } catch (IllegalArgumentException iae) {
            // Bubble the malformed-header IAE up so the controller
            // returns 400 (same convention as every other admin path).
            throw iae;
        } catch (IOException ioe) {
            log.warn("USPS void reconciliation IO failure: {}", ioe.getMessage());
            errors.add("Failed to read CSV: " + ioe.getMessage());
        }

        log.info("USPS void reconciliation done — processed={}, approved={}, denied={}, pending={}, skipped={}, errors={}.",
                processed, approved, denied, pending, skipped, errors.size());
        return new UspsVoidReconciliationSummaryDTO(
                processed, approved, denied, pending, skipped, List.copyOf(errors));
    }

    // ================================================================
    // Per-row reconciliation
    // ================================================================

    private enum Outcome { APPROVED, DENIED, PENDING, SKIPPED_UNKNOWN, SKIPPED_ALREADY, SKIPPED_NOT_VOIDED, MALFORMED }

    private Outcome reconcileRow(String line, Map<String, Integer> headerIndex) {
        String[] cols = splitCsv(line);
        String trackingNumber = readCol(cols, headerIndex, "TrackingNumber");
        String refundStatus = readCol(cols, headerIndex, "RefundStatus");
        if (isBlank(trackingNumber) || isBlank(refundStatus)) {
            return Outcome.MALFORMED;
        }

        Optional<OrderTracking> found = orderTrackingRepository
                .findByTrackingNumberIgnoreCase(trackingNumber.trim());
        if (found.isEmpty()) {
            log.info("USPS void reconciliation: tracking {} not found in local DB, skipping.",
                    trackingNumber);
            return Outcome.SKIPPED_UNKNOWN;
        }
        OrderTracking tracking = found.get();

        // Idempotency guard — never overwrite a terminal reconciliation
        // status. Second-pass runs on the same CSV land here and count
        // as skipped (with no state change).
        String existing = tracking.getVoidReconciliationStatus();
        if (RECONCILED_APPROVED.equalsIgnoreCase(existing)
                || RECONCILED_DENIED.equalsIgnoreCase(existing)) {
            return Outcome.SKIPPED_ALREADY;
        }

        String normalizedStatus = refundStatus.trim().toUpperCase(Locale.ROOT);
        LocalDateTime now = LocalDateTime.now();

        // Only touch rows whose local status was flipped to VOIDED by
        // the optimistic void flow. A row that was never voided
        // shouldn't be dragged into VOID_FAILED just because USPS
        // returned an unrelated line item.
        String localStatus = tracking.getStatus();
        boolean isLocallyVoided = STATUS_VOIDED.equalsIgnoreCase(localStatus);

        switch (normalizedStatus) {
            case "APPROVED" -> {
                if (!isLocallyVoided) {
                    log.info("USPS void reconciliation: tracking {} refund APPROVED at USPS but local status is {} (not VOIDED) — skipping.",
                            trackingNumber, localStatus);
                    return Outcome.SKIPPED_NOT_VOIDED;
                }
                tracking.setVoidReconciliationStatus(RECONCILED_APPROVED);
                tracking.setVoidReconciliationCheckedAt(now);
                orderTrackingRepository.save(tracking);
                return Outcome.APPROVED;
            }
            case "DENIED" -> {
                if (!isLocallyVoided) {
                    log.info("USPS void reconciliation: tracking {} refund DENIED at USPS but local status is {} (not VOIDED) — skipping.",
                            trackingNumber, localStatus);
                    return Outcome.SKIPPED_NOT_VOIDED;
                }
                tracking.setStatus(STATUS_VOID_FAILED);
                tracking.setVoidReconciliationStatus(RECONCILED_DENIED);
                tracking.setVoidReconciliationCheckedAt(now);
                orderTrackingRepository.save(tracking);
                // WARN log so operators can spot flip candidates in the
                // logs page while the FE toast wiring lands in a
                // follow-up PR (see PR-D non-goals).
                log.warn("USPS void reconciliation: tracking {} was flipped to VOID_FAILED — USPS refused the refund (likely scanned in transit). Operator toast required.",
                        trackingNumber);
                return Outcome.DENIED;
            }
            case "PENDING" -> {
                // Leave the row alone — do NOT stamp checkedAt because
                // idempotent re-runs of the same PENDING report should
                // stay a no-op rather than eating processing time.
                log.info("USPS void reconciliation: tracking {} still PENDING at USPS — leaving local state alone.",
                        trackingNumber);
                return Outcome.PENDING;
            }
            default -> {
                log.info("USPS void reconciliation: tracking {} has unknown refund status '{}' — treating as PENDING.",
                        trackingNumber, refundStatus);
                return Outcome.PENDING;
            }
        }
    }

    // ================================================================
    // CSV parsing helpers — package-visible so the unit test can
    // exercise them directly.
    // ================================================================

    /**
     * Build a case-insensitive column-name → position index from the
     * report's header row. Throws {@link IllegalArgumentException} with
     * an actionable message when required columns are missing so the
     * admin controller returns a 400 with a useful body.
     */
    static Map<String, Integer> parseHeader(String headerLine) {
        String[] cols = splitCsv(headerLine);
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < cols.length; i++) {
            String col = cols[i].trim();
            if (!col.isEmpty()) idx.put(col.toLowerCase(Locale.ROOT), i);
        }
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_HEADERS) {
            if (!idx.containsKey(required.toLowerCase(Locale.ROOT))) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "eVS Refund report CSV is missing required column(s): "
                            + String.join(", ", missing)
                            + ". Expected header at minimum: "
                            + String.join(", ", REQUIRED_HEADERS)
                            + ". Actual header: " + headerLine);
        }
        return idx;
    }

    /** Simple comma split — the USPS report is plain CSV without
     *  embedded commas or quoted fields for the columns we consume. If
     *  future column additions include quoted strings, swap this for
     *  Apache Commons CSV. */
    static String[] splitCsv(String line) {
        return Arrays.stream(line.split(",", -1))
                .map(String::trim)
                .toArray(String[]::new);
    }

    private static String readCol(String[] cols, Map<String, Integer> headerIndex, String name) {
        Integer position = headerIndex.get(name.toLowerCase(Locale.ROOT));
        if (position == null) return null;
        if (position >= cols.length) return null;
        return cols[position];
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
