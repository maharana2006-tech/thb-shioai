package com.multiship.backend.service.carriers.usps;

import com.multiship.backend.dto.UspsReconciliationRollupDTO;
import com.multiship.backend.dto.UspsVoidReconciliationSummaryDTO;
import com.multiship.backend.events.AppEventBus;
import com.multiship.backend.events.VoidFailedEvent;
import com.multiship.backend.model.Order;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * releases and we don't want to fail-closed on those. When USPS
 * publishes a free-text reason column ({@code RefundReason} — added
 * ad-hoc in 2024's mid-year update, absent on older reports) the
 * DENIED-branch event carries it verbatim; on reports without the
 * column the {@code uspsReason} field on the event is null.
 *
 * <p>Per-row transitions (assumes the local status is currently
 * {@code VOIDED}):
 * <ul>
 *   <li>{@code APPROVED} → mark
 *       {@code void_reconciliation_status = RECONCILED_APPROVED};
 *       {@code status} stays {@code VOIDED}. Happy path.</li>
 *   <li>{@code DENIED} → flip {@code status = VOID_FAILED},
 *       mark {@code void_reconciliation_status = RECONCILED_DENIED};
 *       WARN log stays as offline-ops fallback and a
 *       {@link VoidFailedEvent} is published on the shared event bus
 *       for the real-time operator toast (subscribed by the FE
 *       {@code useVoidFailedToast} hook via the SSE endpoint).</li>
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
 * them; the row is counted as {@code skipped} with a note. A
 * consequence: {@link VoidFailedEvent}s are ONLY published on the
 * transition (first time a DENIED row is processed) — a re-upload of
 * the same report never re-fires the toast.
 *
 * <p>PR-F4 adds {@link #getRollup(Duration)} - aggregate counts +
 * pending refund value for the admin dashboard.
 */
@Slf4j
@Service
public class UspsDirectVoidReconciliationService {

    private final OrderTrackingRepository orderTrackingRepository;

    /**
     * Optional — used to resolve a per-DENIED-row tenant scope
     * ({@code tenant_id} with a {@code cust_no} fallback, mirroring
     * the app-wide {@code COALESCE(tenant_id, cust_no)} convention)
     * for the emitted {@link VoidFailedEvent}. Nullable so the pure-
     * Mockito unit tests can construct the service without stubbing
     * a whole repository. Null orderNo / missing Order row / null
     * repo all funnel to a null tenant on the event — the SSE
     * controller treats null-tenant events as everyone-visible, which
     * is the safe default when the operator toast would otherwise
     * silently disappear.
     */
    private final OrderRepository orderRepository;

    /**
     * Optional — used to publish {@link VoidFailedEvent}s on the
     * DENIED branch. Nullable for the same reason as
     * {@link #orderRepository}: the older single-arg constructor
     * stays legal for tests that predate this follow-up, and a null
     * bus short-circuits the publish (the WARN log remains as the
     * offline-ops fallback).
     */
    private final AppEventBus appEventBus;

    /**
     * EntityManager for the PR-F4 rollup query. Package-visible +
     * field-injected via {@link PersistenceContext} because the rollup
     * is a single-shot GROUP BY that doesn't warrant its own repo
     * method + JPQL constructor projection. Nullable in unit tests -
     * {@link #setEntityManagerForTest(EntityManager)} lets those tests
     * inject a stub or leave it null (the rollup method returns an
     * empty DTO when null).
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Spring-preferred constructor - wires the OrderRepository and
     * AppEventBus needed for the DENIED-branch VoidFailedEvent
     * publish. Both are optional so the legacy no-event unit tests
     * ({@link #UspsDirectVoidReconciliationService(OrderTrackingRepository)})
     * still construct cleanly.
     */
    @Autowired
    public UspsDirectVoidReconciliationService(OrderTrackingRepository orderTrackingRepository,
                                                OrderRepository orderRepository,
                                                AppEventBus appEventBus) {
        this.orderTrackingRepository = orderTrackingRepository;
        this.orderRepository = orderRepository;
        this.appEventBus = appEventBus;
    }

    /**
     * Legacy constructor preserved for pure-Mockito tests that were
     * written before the event-publish follow-up landed. A service
     * built through this path silently no-ops the event publish
     * (bus == null) — mirrors the "Redis not configured" NO-OP mode.
     */
    public UspsDirectVoidReconciliationService(OrderTrackingRepository orderTrackingRepository) {
        this(orderTrackingRepository, null, null);
    }

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

    /** Ad-hoc reason column USPS added in 2024. Absent on older
     *  reports — treated as null. */
    private static final String REASON_HEADER = "RefundReason";

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

                // Free-text reason from the (optional) RefundReason
                // column — passed through to the operator toast so
                // "label already scanned" vs "account closed" vs
                // "past 30-day window" can be triaged at a glance.
                String uspsReason = readCol(cols, headerIndex, REASON_HEADER);

                // WARN log stays as the offline-ops fallback: even
                // when the event bus is down / Redis is off, the
                // reconciliation history is discoverable in the log
                // file — a real-time push is a UX upgrade, not the
                // sole channel.
                log.warn("USPS VOID_FAILED: order {} tracking {} — USPS denied refund. Reason: {}",
                        Objects.toString(tracking.getOrderNo(), "?"), trackingNumber, orEmpty(uspsReason));

                publishVoidFailedEvent(tracking, uspsReason);
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

    /**
     * Fire-and-forget publish of the operator toast event. Wrapped in
     * a broad catch so a Redis outage / serialization glitch / null
     * bus never breaks the reconciliation loop — the reconciliation
     * writes and the WARN log are the source of truth; the event
     * publish is UX polish that must fail silently.
     *
     * <p>Publishing happens INSIDE the {@link Transactional}
     * reconciliation method so the event surfaces as part of the same
     * unit of work as the status flip; {@link AppEventBus} itself is
     * not transaction-aware (it writes straight to the Redis Stream)
     * so the toast lands the instant the flip completes rather than
     * waiting for commit. Given the flip and event carry the same
     * tracking number, a subsequent transaction rollback would leave
     * the operator with a stale toast — the reconciliation service
     * only rolls back on IllegalArgumentException from a malformed
     * header (before any DENIED row is processed) or IOException on
     * the stream (rare), so the leak window is narrow enough not to
     * warrant a full transaction-synchronised publisher.
     */
    private void publishVoidFailedEvent(OrderTracking tracking, String uspsReason) {
        if (appEventBus == null) {
            log.debug("VoidFailedEvent publish skipped — event bus not wired (unit-test or NO-OP mode).");
            return;
        }
        try {
            Long orderNo = tracking.getOrderNo() == null ? null : tracking.getOrderNo().longValue();
            String tenant = resolveTenantForOrder(tracking.getOrderNo());
            VoidFailedEvent event = new VoidFailedEvent(
                    VoidFailedEvent.EVENT_TYPE,
                    tenant,
                    orderNo,
                    tracking.getTrackingNumber(),
                    uspsReason,
                    LocalDateTime.now(ZoneOffset.UTC));
            appEventBus.publish(event);
        } catch (Exception ex) {
            // Publishing must never break the caller. The WARN log
            // above is the durable record; the toast is polish.
            log.debug("VoidFailedEvent publish for tracking {} threw: {}",
                    tracking.getTrackingNumber(), ex.getMessage());
        }
    }

    /**
     * Resolve a tenant scope for the VoidFailedEvent by peeking at
     * the order. Returns null when the order isn't found or the
     * repository wasn't wired (legacy test constructor). Null tenant
     * on the event → the SSE controller treats it as everyone-visible;
     * we prefer that failure mode to silently suppressing operator
     * toasts.
     */
    private String resolveTenantForOrder(Integer orderNo) {
        if (orderNo == null || orderRepository == null) return null;
        try {
            Optional<Order> order = orderRepository.findByOrderNo(orderNo);
            return order.map(o -> StringUtils.hasText(o.getTenantId())
                            ? o.getTenantId()
                            : o.getCustNo())
                    .orElse(null);
        } catch (Exception ex) {
            log.debug("resolveTenantForOrder({}) threw: {}", orderNo, ex.getMessage());
            return null;
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
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

    // ================================================================
    // PR-F4 - admin-dashboard rollup
    // ================================================================

    /**
     * PR-F4 - rollup of the USPS_DIRECT void reconciliation state
     * over the last {@code lookback} window. Aggregates
     * {@code void_reconciliation_status} on {@code order_label_tracking}
     * for rows where {@code UPPER(status) = 'VOIDED'} AND
     * {@code UPPER(ship_via_cd) LIKE 'USPS%'} (USPS carrier scope,
     * mirrors {@code findVoidedUnreconciledUspsBetween}) AND
     * {@code label_generated_at &gt;= now() - lookback}.
     *
     * <p>Groups by reconciliation status, tallies:
     * <ul>
     *   <li>voidedShipmentsInWindow - all matching rows.</li>
     *   <li>reconciledApproved - {@code RECONCILED_APPROVED} bucket.</li>
     *   <li>reconciledDenied - {@code RECONCILED_DENIED} bucket.</li>
     *   <li>notYetReconciled - {@code voidReconciliationStatus IS NULL}
     *       bucket.</li>
     *   <li>lastReconciliationAt - {@code max(void_reconciliation_checked_at)}
     *       across the window; {@code null} on an empty window.</li>
     *   <li>pendingRefundValue - {@code sum(carrier_amount)} for rows
     *       where reconciliation status is null; best-effort (zero when
     *       the column wasn't populated at label time).</li>
     * </ul>
     *
     * <p>JPQL rather than native so the query runs against H2 / Postgres
     * without dialect tweaks; the entity manager is null-safe (unit
     * tests without a persistence context get an empty rollup).
     *
     * @param lookback non-null rolling window. Zero / negative /
     *                 exceeding {@code Long.MAX_VALUE.toDays()} clamps
     *                 to the default 30 days.
     */
    @Transactional(readOnly = true)
    public UspsReconciliationRollupDTO getRollup(Duration lookback) {
        Duration window = (lookback == null || lookback.isZero() || lookback.isNegative())
                ? Duration.ofDays(30)
                : lookback;
        int lookbackDays = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, window.toDays()));
        LocalDateTime from = LocalDateTime.now().minus(window);

        if (entityManager == null) {
            // No persistence context - degrade to empty rollup so
            // callers don't NPE. Prod always has a real EM.
            return emptyRollup(lookbackDays);
        }

        long voidedTotal = countVoidedInWindow(from);
        long approvedCount = countByReconStatus(from, RECONCILED_APPROVED);
        long deniedCount = countByReconStatus(from, RECONCILED_DENIED);
        long pendingCount = countUnreconciled(from);
        LocalDateTime lastCheckedAt = maxReconciliationCheckedAt(from);
        BigDecimal pendingRefundValue = Objects.requireNonNullElse(
                sumPendingRefundValue(from), BigDecimal.ZERO);

        return UspsReconciliationRollupDTO.builder()
                .lookbackDays(lookbackDays)
                .voidedShipmentsInWindow(voidedTotal)
                .reconciledApproved(approvedCount)
                .reconciledDenied(deniedCount)
                .notYetReconciled(pendingCount)
                .lastReconciliationAt(lastCheckedAt)
                .pendingRefundValue(pendingRefundValue)
                .currency("USD")
                .build();
    }

    private long countVoidedInWindow(LocalDateTime from) {
        Long v = entityManager.createQuery("""
                SELECT COUNT(t) FROM OrderTracking t
                 WHERE UPPER(t.status) = 'VOIDED'
                   AND UPPER(COALESCE(t.shipViaCd, '')) LIKE 'USPS%'
                   AND t.labelGeneratedAt >= :from
                """, Long.class)
                .setParameter("from", from)
                .getSingleResult();
        return v == null ? 0L : v;
    }

    private long countByReconStatus(LocalDateTime from, String status) {
        Long v = entityManager.createQuery("""
                SELECT COUNT(t) FROM OrderTracking t
                 WHERE UPPER(t.status) = 'VOIDED'
                   AND UPPER(COALESCE(t.shipViaCd, '')) LIKE 'USPS%'
                   AND t.labelGeneratedAt >= :from
                   AND UPPER(t.voidReconciliationStatus) = :status
                """, Long.class)
                .setParameter("from", from)
                .setParameter("status", status.toUpperCase(Locale.ROOT))
                .getSingleResult();
        return v == null ? 0L : v;
    }

    private long countUnreconciled(LocalDateTime from) {
        Long v = entityManager.createQuery("""
                SELECT COUNT(t) FROM OrderTracking t
                 WHERE UPPER(t.status) = 'VOIDED'
                   AND UPPER(COALESCE(t.shipViaCd, '')) LIKE 'USPS%'
                   AND t.labelGeneratedAt >= :from
                   AND t.voidReconciliationStatus IS NULL
                """, Long.class)
                .setParameter("from", from)
                .getSingleResult();
        return v == null ? 0L : v;
    }

    private LocalDateTime maxReconciliationCheckedAt(LocalDateTime from) {
        return entityManager.createQuery("""
                SELECT MAX(t.voidReconciliationCheckedAt) FROM OrderTracking t
                 WHERE UPPER(t.status) = 'VOIDED'
                   AND UPPER(COALESCE(t.shipViaCd, '')) LIKE 'USPS%'
                   AND t.labelGeneratedAt >= :from
                """, LocalDateTime.class)
                .setParameter("from", from)
                .getSingleResult();
    }

    private BigDecimal sumPendingRefundValue(LocalDateTime from) {
        return entityManager.createQuery("""
                SELECT COALESCE(SUM(t.carrierAmount), 0) FROM OrderTracking t
                 WHERE UPPER(t.status) = 'VOIDED'
                   AND UPPER(COALESCE(t.shipViaCd, '')) LIKE 'USPS%'
                   AND t.labelGeneratedAt >= :from
                   AND t.voidReconciliationStatus IS NULL
                """, BigDecimal.class)
                .setParameter("from", from)
                .getSingleResult();
    }

    private static UspsReconciliationRollupDTO emptyRollup(int lookbackDays) {
        return UspsReconciliationRollupDTO.builder()
                .lookbackDays(lookbackDays)
                .voidedShipmentsInWindow(0L)
                .reconciledApproved(0L)
                .reconciledDenied(0L)
                .notYetReconciled(0L)
                .lastReconciliationAt(null)
                .pendingRefundValue(BigDecimal.ZERO)
                .currency("USD")
                .build();
    }

    // Test hook - inject an EntityManager stub without a Spring context.
    void setEntityManagerForTest(EntityManager em) {
        this.entityManager = em;
    }
}
