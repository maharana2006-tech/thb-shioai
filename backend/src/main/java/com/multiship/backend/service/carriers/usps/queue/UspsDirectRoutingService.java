package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.model.Order;
import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.repository.UspsLabelQueueRepository;
import com.multiship.backend.service.ShippingConfigService;
import com.multiship.backend.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

/**
 * PR-G1 — shared routing decision for the USPS_DIRECT persistent label
 * queue. Extracted from {@code BulkLabelServiceImpl.maybeEnqueueUspsDirect}
 * (PR-F1) so all three label-generating entry points can consult one
 * authoritative decision engine instead of drifting per-caller:
 *
 * <ul>
 *   <li>{@code BulkLabelServiceImpl.processOneOrder} — operator-triggered
 *       bulk label modal (already routed under PR-F1; now delegates
 *       through this service).</li>
 *   <li>{@code CarrierServiceImpl.generateLabel(Long, UserDetails, String, Long)}
 *       — list-view Generate button, {@code POST /orders/{n}/label}
 *       endpoint, and the queue-processor callback itself (see the
 *       re-entrancy guard below).</li>
 *   <li>{@code CarrierServiceImpl.generateManualLabel(ManualShipmentRequest, UserDetails, Integer)}
 *       — {@code /orders/new} save and {@code /orders/manual-label}
 *       (once the fresh orderNo is known).</li>
 * </ul>
 *
 * <p>Every entry point invokes {@link #decide(long, UserDetails)} with
 * the resolved orderNo + the incoming caller. The routing service inspects
 * platform state + the order and returns one of four decisions:
 *
 * <ol>
 *   <li>{@code Optional.empty()} — no routing applies; the caller
 *       proceeds down its existing sync path (STAMPS_COM provider,
 *       non-USPS carrier, unwired collaborator, re-entrant caller).</li>
 *   <li>{@link RoutingDecision.Status#SINGLE_QUEUED} — single-package
 *       USPS order enqueued as one queue row (PR-F1 shape).</li>
 *   <li>{@link RoutingDecision.Status#MPS_QUEUED} — multi-package
 *       USPS domestic order fanned into N queue rows (PR-F2 shape).</li>
 *   <li>{@link RoutingDecision.Status#REJECTED} — intl-MPS refused
 *       before any queue rows land ({@code INTL_MPS_UNSUPPORTED}).</li>
 * </ol>
 *
 * <p><b>Re-entrancy guard.</b> The queue processor's callback
 * ({@code UspsLabelQueueWiring.processQueueItem}) dispatches to
 * {@code carrierService.generateLabel(orderNo, systemUser, ...)}. Without
 * a guard, {@code generateLabel}'s call to this service would enqueue the
 * order a SECOND time, producing an infinite fan-out. We detect the
 * queue-processor synthetic username ({@code UspsLabelQueueWiring.QUEUE_SYSTEM_USER})
 * on the incoming {@link UserDetails} and short-circuit to {@code SYNC}
 * so the connector call actually fires.
 *
 * <p><b>Intl-MPS peer guard.</b> {@code UspsMpsSplitterService} already
 * rejects intl MPS at enqueue time (PR shipped 2026-09-16 / commit
 * f76e7645 / PR #661); this service adds a peer guard at
 * <em>routing-decision</em> time so callers see one consistent verdict
 * (REJECTED) instead of an opaque splitter IllegalArgumentException.
 * Defense in depth — either guard alone stops the bad request; both
 * make sure operators get an actionable message from the first layer
 * that inspects the order.
 *
 * <p><b>PR-G3b - provenance.</b> Every caller passes a {@link ProvenanceHint}
 * describing which surface triggered the routing decision (bulk vs
 * import operator vs background vs manual). The hint plumbs through to
 * {@link UspsLabelQueueService#enqueue}'s {@code sourceType} +
 * {@code importBatchId} so the admin dashboard's by-source aggregation
 * can attribute quota consumption to the real triggering surface and so
 * {@code cancelPending(importBatchId)} can cascade a cancelled batch.
 * The one-arg {@link #decide(long, UserDetails)} overload uses
 * {@link ProvenanceHint#unknown()} for pre-G3b callers that haven't
 * updated to the two-arg form.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UspsDirectRoutingService {

    private final SystemSettingService systemSettingService;
    private final OrderRepository orderRepository;
    private final UspsLabelQueueService uspsLabelQueueService;
    private final UspsMpsSplitterService uspsMpsSplitterService;
    private final UspsLabelQueueRepository uspsLabelQueueRepository;

    /**
     * Legacy 2-arg overload for pre-PR-G3b callers. Delegates to
     * {@link #decide(long, UserDetails, ProvenanceHint)} with
     * {@link ProvenanceHint#unknown()} so rows land without a source
     * stamp (dashboard shows them in the UNKNOWN bucket). Prefer the
     * 3-arg overload for new code.
     */
    public Optional<RoutingDecision> decide(long orderNo, UserDetails caller) {
        return decide(orderNo, caller, ProvenanceHint.unknown());
    }

    /**
     * PR-G3b - decide how to route the label call for {@code orderNo}
     * with an explicit provenance hint stamped onto every persisted
     * queue row. See class javadoc for the four possible outcomes.
     *
     * @param orderNo  local order number (positive long — synthetic MPS
     *                 shipmentIds never reach here because the splitter
     *                 owns them).
     * @param caller   the user driving the label call. Nullable for
     *                 background callers with no HTTP context (e.g. the
     *                 bulk-label worker); the re-entrancy guard only
     *                 fires when {@code caller != null} and the username
     *                 matches {@link UspsLabelQueueWiring#QUEUE_SYSTEM_USER}.
     * @param hint     PR-G3b - which surface caused the routing call.
     *                 Non-null (use {@link ProvenanceHint#unknown()} if
     *                 the caller genuinely has no source info); the hint
     *                 is stamped onto every persisted queue row so the
     *                 admin dashboard can attribute quota consumption.
     * @return empty when the caller should stay on its sync path; a
     *         {@link RoutingDecision} otherwise.
     */
    public Optional<RoutingDecision> decide(long orderNo, UserDetails caller, ProvenanceHint hint) {
        ProvenanceHint safeHint = hint == null ? ProvenanceHint.unknown() : hint;
        // Re-entrancy guard — the queue processor callback runs under
        // the synthetic 'usps-queue-processor' username (see
        // UspsLabelQueueWiring.java:72 for the constant). Without this
        // check, generateLabel invoked from that callback would enqueue
        // the same order a second time on every tick, producing an
        // infinite fan-out. Only fires when the caller identifies —
        // background callers (BulkLabelServiceImpl, etc.) pass null.
        if (caller != null
                && UspsLabelQueueWiring.QUEUE_SYSTEM_USER.equals(caller.getUsername())) {
            log.debug("PR-G1: re-entrant call from queue processor for order {} — sync path", orderNo);
            return Optional.empty();
        }

        // Provider setting lookup — a broken lookup falls through to sync
        // so a settings-service outage can't brick label generation
        // platform-wide. Matches BulkLabelServiceImpl.maybeEnqueueUspsDirect.
        String provider;
        try {
            provider = systemSettingService.getDecrypted("USPS_PROVIDER").orElse("STAMPS_COM");
        } catch (Exception ex) {
            log.debug("PR-G1: USPS_PROVIDER lookup failed; sync fallback. {}", ex.getMessage());
            return Optional.empty();
        }
        if (!"USPS_DIRECT".equalsIgnoreCase(provider)) {
            return Optional.empty();
        }

        // Order lookup — a missing row means the caller will blow up
        // shortly on its own path; nothing for us to enqueue.
        Optional<Order> orderOpt = orderRepository.findByOrderNo((int) orderNo);
        if (orderOpt.isEmpty()) {
            log.warn("PR-G1: order {} not found during routing decision — sync fallback", orderNo);
            return Optional.empty();
        }
        Order order = orderOpt.get();

        // Carrier gate — only USPS orders go through the 55/hr platform
        // queue. FedEx / UPS / DHL etc. stay on the sync path.
        String canonical = ShippingConfigService.canonicalCarrierFor(order.getShipviaCd());
        if (canonical == null || !"USPS".equalsIgnoreCase(canonical)) {
            return Optional.empty();
        }

        // PR-G5 D3 — auth-time tenant scope wins when the caller supplied one
        // (import path passes job.getRequestedScope() so the queue row is
        // scoped to what the operator was actually allowed to see, not
        // whatever the loaded Order row happens to carry). Falls back to
        // the pre-G5 order-derived chain so manual + bulk-operator paths
        // (which never had a scope hint) behave unchanged.
        String tenantCode;
        if (StringUtils.hasText(safeHint.tenantCodeHint())) {
            tenantCode = safeHint.tenantCodeHint().trim();
        } else if (StringUtils.hasText(order.getTenantId())) {
            tenantCode = order.getTenantId();
        } else if (StringUtils.hasText(order.getCustNo())) {
            tenantCode = order.getCustNo();
        } else {
            tenantCode = "unknown";
        }

        Integer packageCount = order.getPackageCount();
        boolean isMps = packageCount != null && packageCount >= 2;

        // Intl-MPS pre-check.
        //
        // REGULATORY_REFERENCE: docs/usps-direct-integration.md §11 ("Open
        // risks + mitigations") — USPS APIs v3 have NO batch endpoint for
        // international shipments. Every intl piece needs its own carrier
        // call, and the per-piece dispatcher (UspsMpsPieceDispatcher /
        // PR-F2.5) builds a lean DTO that skips the customs cascade — an
        // intl MPS order would therefore reach USPS with a customs-less
        // payload and get rejected with an opaque error. Refuse LOUD at
        // decision time (peer to the enqueue-time guard in
        // UspsMpsSplitterService that shipped as PR #661) so operators
        // see the actionable REJECTED verdict from the first layer that
        // inspects the order.
        if (isMps) {
            String rawCountry = order.getShiptoCountryCd();
            String country = rawCountry == null ? "" : rawCountry.trim();
            boolean isIntl = !country.isEmpty() && !"US".equalsIgnoreCase(country);
            if (isIntl) {
                String reason = "USPS Direct does not support multi-piece international shipments. "
                        + "Split into single-package intl shipments manually, or set USPS_PROVIDER=STAMPS_COM "
                        + "in /settings/system.";
                log.warn("PR-G1: order {} rejected — intl MPS ({} pieces to country={}) "
                                + "under USPS_DIRECT provider (peer guard for UspsMpsSplitterService)",
                        orderNo, packageCount, country.toUpperCase(Locale.ROOT));
                return Optional.of(RoutingDecision.rejected(reason));
            }
        }

        // MPS branch — domestic multi-piece USPS. Fan into N queue rows
        // sharing parent_order_no so /mps-progress/{orderNo} aggregates.
        if (isMps) {
            try {
                UspsLabelQueueService.EnqueueMpsResult result = uspsMpsSplitterService
                        .splitAndEnqueueForOrder(orderNo, packageCount, tenantCode,
                                safeHint.sourceType(), safeHint.importBatchId());
                if (result == null || result.enqueuedCount() <= 0) {
                    log.warn("PR-G1: USPS Direct MPS enqueue returned no rows for order {} — sync fallback",
                            orderNo);
                    return Optional.empty();
                }
                log.info("PR-G1: order {} routed to USPS Direct MPS queue (pieces={}, tenant={}, "
                                + "firstStart={}, lastComplete={}, source={}, importBatch={})",
                        orderNo, result.enqueuedCount(), tenantCode,
                        result.estimatedFirstStartAt(), result.estimatedLastCompleteAt(),
                        safeHint.sourceType(), safeHint.importBatchId());
                return Optional.of(RoutingDecision.mpsQueued(result.enqueuedCount()));
            } catch (IllegalArgumentException iae) {
                // Splitter's fail-fast validation (intl-MPS peer, sub-2
                // packageCount, blank tenantCode). Surface the splitter's
                // own message so operators see the exact remediation
                // regardless of which guard layer catches the problem.
                log.warn("PR-G1: USPS Direct MPS enqueue rejected for order {}: {}",
                        orderNo, iae.getMessage());
                return Optional.of(RoutingDecision.rejected(iae.getMessage()));
            } catch (IllegalStateException dup) {
                // UspsLabelQueueServiceImpl.enqueueMps wraps a
                // DataIntegrityViolationException on the UNIQUE(shipment_id)
                // constraint into IllegalStateException so callers have one
                // exception type to catch across single + MPS enqueue.
                // Concurrent enqueue for the same parent — the OTHER writer
                // already persisted an MPS batch under this shipmentId.
                // Treat as a successful enqueue rather than a duplicate:
                // the queue is doing its job, we just lost the race.
                UspsLabelQueueItem existing = lookupFirstPiece(orderNo);
                if (existing == null) {
                    log.warn("PR-G1: MPS enqueue for order {} raised IllegalStateException but "
                                    + "no existing row found — sync fallback. reason={}",
                            orderNo, dup.getMessage());
                    return Optional.empty();
                }
                log.info("PR-G1: MPS enqueue for order {} lost concurrent-writer race — "
                                + "treating existing batch as queued (pieces={})",
                        orderNo, packageCount);
                return Optional.of(RoutingDecision.mpsQueued(packageCount));
            } catch (DataIntegrityViolationException dup) {
                // Belt-and-braces: if a future refactor removes the
                // service-side IllegalStateException wrap, catch the raw
                // JPA exception here so behavior stays the same.
                UspsLabelQueueItem existing = lookupFirstPiece(orderNo);
                if (existing == null) {
                    log.warn("PR-G1: MPS enqueue for order {} raised DataIntegrityViolationException "
                                    + "but no existing row found — sync fallback. cause={}",
                            orderNo,
                            dup.getMostSpecificCause() != null
                                    ? dup.getMostSpecificCause().getMessage()
                                    : dup.getMessage());
                    return Optional.empty();
                }
                log.info("PR-G1: MPS enqueue for order {} lost concurrent-writer race (raw DIVE) — "
                                + "treating existing batch as queued (pieces={})",
                        orderNo, packageCount);
                return Optional.of(RoutingDecision.mpsQueued(packageCount));
            }
        }

        // Single-label branch — one queue row (PR-F1 shape).
        try {
            UspsLabelQueueService.EnqueueResult result = uspsLabelQueueService.enqueue(
                    new UspsLabelQueueService.EnqueueRequest(
                            tenantCode, orderNo, 0,
                            safeHint.sourceType(),
                            safeHint.importBatchId()));
            if (result == null || result.queueItemId() == null) {
                log.warn("PR-G1: USPS Direct enqueue returned null for order {} — sync fallback", orderNo);
                return Optional.empty();
            }
            log.info("PR-G1: order {} routed to USPS Direct queue (item {}, tenant {}, est start {}, source {}, importBatch {})",
                    orderNo, result.queueItemId(), tenantCode, result.estimatedStartAt(),
                    safeHint.sourceType(), safeHint.importBatchId());
            return Optional.of(RoutingDecision.singleQueued(result.queueItemId()));
        } catch (IllegalStateException dup) {
            // UspsLabelQueueServiceImpl.enqueue throws IllegalStateException
            // both on the pre-check ("Shipment X is already queued") AND on
            // the concurrent-writer race (unique-constraint DataIntegrityViolationException
            // wrapped). Fetch the existing row so the caller can pin their
            // UI to the live queue item.
            UspsLabelQueueItem existing = uspsLabelQueueRepository.findByShipmentId(orderNo).orElse(null);
            if (existing == null) {
                log.warn("PR-G1: single-label enqueue for order {} raised IllegalStateException but "
                                + "no existing row found — sync fallback. reason={}",
                        orderNo, dup.getMessage());
                return Optional.empty();
            }
            log.info("PR-G1: order {} already queued as item {} (status {}) — reusing existing row",
                    orderNo, existing.getId(), existing.getStatus());
            return Optional.of(RoutingDecision.singleQueued(existing.getId()));
        }
    }

    /**
     * MPS duplicate handling — the splitter uses synthetic negative
     * shipmentIds keyed to {@code -(parentOrderNo * 100_000 + seq)} so
     * the first piece's shipmentId reconstructs to
     * {@code -(orderNo * SYNTHETIC_SHIPMENT_ID_MULTIPLIER + 1)}. Return
     * that row if it exists so callers can key their UI to the live
     * batch instead of failing on the DB integrity check.
     */
    private UspsLabelQueueItem lookupFirstPiece(long parentOrderNo) {
        long firstPieceSyntheticId = UspsMpsSplitterService.syntheticShipmentIdFor(parentOrderNo, 1);
        return uspsLabelQueueRepository.findByShipmentId(firstPieceSyntheticId).orElse(null);
    }

    /**
     * PR-G3b - provenance stamp for a routing call. Composed by the
     * caller once and threaded through so every enqueue (single or MPS
     * fan-out) inherits the same source. See
     * {@link UspsLabelQueueItem.SourceType} for the value semantics.
     *
     * <p>{@link #importBatchId} is non-null iff the routing call
     * originated inside an import batch (operator or background). The
     * splitter propagates the value to every persisted MPS piece so
     * {@code cancelPending} catches all N rows in one cascade.
     */
    public record ProvenanceHint(
            UspsLabelQueueItem.SourceType sourceType,
            Long importBatchId,
            String tenantCodeHint) {

        /**
         * PR-G3b two-arg back-compat constructor. Prefer the 3-arg
         * canonical constructor for new code so tenant derivation stays
         * consistent with the caller's auth-time truth (finding D3).
         */
        public ProvenanceHint(UspsLabelQueueItem.SourceType sourceType, Long importBatchId) {
            this(sourceType, importBatchId, null);
        }

        /**
         * Sentinel for callers with no source info (legacy code paths,
         * back-compat overloads). Persisted queue rows land with
         * {@code source_type = NULL} and appear under the dashboard's
         * UNKNOWN bucket.
         */
        public static ProvenanceHint unknown() {
            return new ProvenanceHint(null, null, null);
        }

        /** Manual single-order path (CarrierServiceImpl.maybeRouteUspsDirect). */
        public static ProvenanceHint manual() {
            return new ProvenanceHint(UspsLabelQueueItem.SourceType.MANUAL, null, null);
        }

        /** Bulk-label modal path (BulkLabelServiceImpl.maybeEnqueueUspsDirect). */
        public static ProvenanceHint bulkOperator() {
            return new ProvenanceHint(UspsLabelQueueItem.SourceType.BULK_OPERATOR, null, null);
        }

        /** Import path triggered by an operator inline (jobId==null). */
        public static ProvenanceHint importOperator(Long importBatchId) {
            return new ProvenanceHint(UspsLabelQueueItem.SourceType.IMPORT_OPERATOR, importBatchId, null);
        }

        /** Import path running under the background worker (jobId!=null). */
        public static ProvenanceHint importBackground(Long importBatchId) {
            return new ProvenanceHint(UspsLabelQueueItem.SourceType.IMPORT_BACKGROUND, importBatchId, null);
        }

        /**
         * PR-G5 D3 — import-operator variant with the auth-time tenant
         * scope hint. Prefer over {@link #importOperator(Long)} when the
         * caller knows the tenant from {@code job.getRequestedScope()};
         * routing then derives tenantCode from the hint instead of the
         * order's {@code tenantId}/{@code custNo} fallback chain, so the
         * queue row is scoped to the auth-time truth (finding D3).
         */
        public static ProvenanceHint importOperatorScoped(Long importBatchId, String tenantCodeHint) {
            return new ProvenanceHint(UspsLabelQueueItem.SourceType.IMPORT_OPERATOR,
                    importBatchId, tenantCodeHint);
        }

        /**
         * PR-G5 D3 — import-background variant with the auth-time tenant
         * scope hint. See {@link #importOperatorScoped(Long, String)}.
         */
        public static ProvenanceHint importBackgroundScoped(Long importBatchId, String tenantCodeHint) {
            return new ProvenanceHint(UspsLabelQueueItem.SourceType.IMPORT_BACKGROUND,
                    importBatchId, tenantCodeHint);
        }
    }

    /**
     * Routing verdict returned by {@link #decide(long, UserDetails)}.
     *
     * @param status         which of the four outcomes fired.
     * @param queueItemId    server-assigned queue row id — populated when
     *                       {@code status} is {@link Status#SINGLE_QUEUED}
     *                       (single-label) or when the MPS branch had to
     *                       reuse an existing row on the race path.
     *                       Otherwise null.
     * @param mpsPieceCount  number of queue rows persisted — populated
     *                       only when {@code status} is {@link Status#MPS_QUEUED}.
     * @param reason         actionable remediation string — populated
     *                       only when {@code status} is {@link Status#REJECTED}.
     */
    public record RoutingDecision(
            Status status,
            Long queueItemId,
            Integer mpsPieceCount,
            String reason) {

        /**
         * The four possible routing outcomes. See {@link UspsDirectRoutingService}
         * class javadoc for how each maps back to caller behavior.
         */
        public enum Status {
            /** No routing applies — caller proceeds on its sync path. */
            SYNC,
            /** Single-label enqueue succeeded (PR-F1 shape). */
            SINGLE_QUEUED,
            /** MPS batch enqueue succeeded (PR-F2 shape). */
            MPS_QUEUED,
            /** Intl-MPS refused before enqueue (INTL_MPS_UNSUPPORTED). */
            REJECTED
        }

        /** True when the order landed on the queue (single or MPS). */
        public boolean isEnqueued() {
            return status == Status.SINGLE_QUEUED || status == Status.MPS_QUEUED;
        }

        /** True when the routing decision refused to enqueue the order. */
        public boolean isRejected() {
            return status == Status.REJECTED;
        }

        // ----- factories -----

        static RoutingDecision singleQueued(Long queueItemId) {
            return new RoutingDecision(Status.SINGLE_QUEUED, queueItemId, null, null);
        }

        static RoutingDecision mpsQueued(int mpsPieceCount) {
            return new RoutingDecision(Status.MPS_QUEUED, null, mpsPieceCount, null);
        }

        static RoutingDecision rejected(String reason) {
            return new RoutingDecision(Status.REJECTED, null, null, reason);
        }
    }
}
