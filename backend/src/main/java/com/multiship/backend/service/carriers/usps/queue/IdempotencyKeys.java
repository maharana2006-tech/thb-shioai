package com.multiship.backend.service.carriers.usps.queue;

/**
 * PR-G5 (audit finding D1) — canonical idempotency-key namespaces for
 * USPS_DIRECT flows.
 *
 * <p>Before G5, three surfaces minted three key formats for the same
 * order:
 * <ul>
 *   <li>Queue retry — {@code usps-queue-{queueItemId}} (per-attempt,
 *       so a re-enqueue after FAILED lands under a fresh key and
 *       misses dedup).</li>
 *   <li>Import batch — {@code null} (no dedup at all).</li>
 *   <li>Manual controller — {@code user:{username}} + client header
 *       (client-driven, correctly scoped).</li>
 * </ul>
 * The queue + import cases both talk to the same tracking row, so any
 * cross-flow retry either double-taxed the carrier (import: null key
 * meant no dedup) or 409'd on the tracking row's stored key (queue: the
 * itemId prefix moved between attempts). G5 standardises the two
 * internal surfaces on {@link #forUspsOrder(long)} — a stable,
 * order-anchored key — so a retry from any internal surface returns the
 * first attempt's tracking verbatim instead of failing or double-charging.
 *
 * <p>The client-driven manual path is deliberately untouched: an
 * external caller's {@code Idempotency-Key} header is their contract,
 * not ours to override.
 */
public final class IdempotencyKeys {

    /**
     * Prefix reserved for order-anchored internal keys. Kept short + hyphenated
     * so it's readable in logs / DB rows and cheap in the Redis key space.
     */
    static final String USPS_ORDER_PREFIX = "usps-order-";

    /**
     * Legacy prefix from pre-G5 queue writes. Kept as a constant so
     * audit tooling / diagnostics can recognise old rows.
     */
    static final String LEGACY_USPS_QUEUE_PREFIX = "usps-queue-";

    private IdempotencyKeys() {}

    /**
     * Canonical order-anchored idempotency key for internal USPS_DIRECT
     * flows (queue retry + import row generation). Same key for the same
     * order regardless of which surface fires the label call, so
     * {@code CarrierServiceImpl.generateLabel}'s tracking-row dedup
     * returns the first attempt as a success replay instead of 409ing
     * or re-billing.
     *
     * <p>Non-MPS only. MPS pieces route through a per-piece dispatcher
     * that keys off {@code parentOrderNo + sequenceNumber}; those are
     * scoped separately by {@code UspsMpsPieceDispatcher} and don't
     * share this namespace.
     *
     * @param orderNo  local order number (positive long).
     * @return         canonical order-anchored key.
     * @throws IllegalArgumentException when orderNo is non-positive.
     */
    public static String forUspsOrder(long orderNo) {
        if (orderNo <= 0L) {
            throw new IllegalArgumentException(
                    "IdempotencyKeys.forUspsOrder requires a positive orderNo (got " + orderNo + ").");
        }
        return USPS_ORDER_PREFIX + orderNo;
    }

    /**
     * Pre-G5 queue key format — {@code usps-queue-{queueItemId}}.
     * Retained only so audit tooling can classify old tracking rows;
     * new writes MUST use {@link #forUspsOrder(long)}.
     */
    static String legacyForQueueItem(long queueItemId) {
        return LEGACY_USPS_QUEUE_PREFIX + queueItemId;
    }

    /**
     * PR-S3 (audit D1) — semantic alias for {@link #forUspsOrder(long)}.
     * The internal-key namespace is deliberately carrier-agnostic (the
     * "usps-" prefix is a G5-era historical accident, not a carrier
     * identifier). Any USPS-family label — whether the tenant's USPS_PROVIDER
     * is set to STAMPS_COM (goes through StampsConnector) or USPS_DIRECT
     * (goes through UspsDirectConnector) — should mint the same key for
     * the same {@code orderNo} so that provider-flip mid-batch retries
     * still hit {@code CarrierServiceImpl.generateLabel}'s tracking-row
     * dedup instead of double-charging.
     *
     * <p>Kept as a separate method (not just calling {@code forUspsOrder}
     * directly) so future refactors can migrate to a per-carrier
     * namespace if that becomes necessary without changing the Stamps
     * call sites.
     */
    public static String forStampsOrder(long orderNo) {
        return forUspsOrder(orderNo);
    }
}
