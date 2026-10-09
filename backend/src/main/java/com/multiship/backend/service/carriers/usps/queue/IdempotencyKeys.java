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

    /**
     * D5 — idempotency key for Stamps SERA {@code POST /balance/add-funds}
     * top-ups. Bucket by (accountId, day, hour) so a scheduled poller that
     * fires twice in the same window doesn't double-buy postage. Different
     * hours produce different keys so a legitimate second top-up later in
     * the day still goes through.
     *
     * @param carrierAccountRefId primary key of the Stamps account.
     * @param dayHourBucket       {@code yyyy-MM-dd-HH} in UTC.
     */
    public static String forStampsTopup(long carrierAccountRefId, String dayHourBucket) {
        if (carrierAccountRefId <= 0L) {
            throw new IllegalArgumentException(
                    "IdempotencyKeys.forStampsTopup requires a positive carrierAccountRefId");
        }
        if (dayHourBucket == null || dayHourBucket.isBlank()) {
            throw new IllegalArgumentException(
                    "IdempotencyKeys.forStampsTopup requires a non-blank dayHourBucket");
        }
        // S-track D5 originally returned the raw string "stamps-topup-{id}-{bucket}"
        // but SERA rejects non-UUID-shaped Idempotency-Keys with carrier error
        // 800001 "Idempotency-Key provided was invalid." Coerce to UUID v3 so the
        // key stays deterministic for the same (accountId, bucket) but passes SERA's
        // shape check. Matches the forStampsLabel / forStampsManifest pattern
        // shipped in T2.
        String payload = "stamps-topup|" + carrierAccountRefId + "|" + dayHourBucket;
        return java.util.UUID.nameUUIDFromBytes(
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /**
     * PR-T2 (audit T-L1) — deterministic Idempotency-Key for Stamps SERA
     * {@code POST /sera/v1/labels}. SERA dedupes on this key for 24h; a
     * crash-replay with the SAME (reference, piece, shipDate) now hits the
     * cached first-attempt response instead of printing a second paid
     * label. UUID v3 (name-based) keeps the canonical UUID shape SERA
     * documents on the wire.
     *
     * <p>Piece-indexed because SERA MPS is N × POST — each piece's key
     * must differ or SERA 400s with {@code error_code 800001}. ShipDate
     * is included so re-shipping an order on a later day (after a
     * same-day void) gets a fresh key.
     *
     * @param referenceNumber local order reference; must be non-blank.
     * @param pieceIndex      1-based piece number within the MPS.
     * @param shipDate        the ship-date the label body uses.
     */
    public static String forStampsLabel(String referenceNumber, int pieceIndex,
                                        java.time.LocalDate shipDate) {
        if (referenceNumber == null || referenceNumber.isBlank()) {
            throw new IllegalArgumentException(
                    "IdempotencyKeys.forStampsLabel requires a non-blank referenceNumber");
        }
        if (pieceIndex < 1) {
            throw new IllegalArgumentException(
                    "IdempotencyKeys.forStampsLabel requires pieceIndex >= 1 (got " + pieceIndex + ")");
        }
        if (shipDate == null) {
            throw new IllegalArgumentException(
                    "IdempotencyKeys.forStampsLabel requires a non-null shipDate");
        }
        String payload = "stamps-label|" + referenceNumber.trim() + "|" + pieceIndex + "|" + shipDate;
        return java.util.UUID.nameUUIDFromBytes(
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /**
     * PR-T2 (audit T-M1) — deterministic Idempotency-Key for Stamps SERA
     * {@code POST /sera/v1/manifests}. Scoped by (accountNumber, closeDate)
     * so a double-click or crash-replay re-manifests the SAME batch under
     * the SAME key instead of generating a duplicate manifest. UUID v3
     * for the same reason as {@link #forStampsLabel}.
     */
    public static String forStampsManifest(String accountNumber,
                                           java.time.LocalDate closeDate) {
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new IllegalArgumentException(
                    "IdempotencyKeys.forStampsManifest requires a non-blank accountNumber");
        }
        if (closeDate == null) {
            throw new IllegalArgumentException(
                    "IdempotencyKeys.forStampsManifest requires a non-null closeDate");
        }
        String payload = "stamps-manifest|" + accountNumber.trim() + "|" + closeDate;
        return java.util.UUID.nameUUIDFromBytes(
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /**
     * PR-T3 (audit §5.1) — deterministic Idempotency-Key for Stamps SERA
     * {@code POST /sera/v1/rates}. SERA requires an Idempotency-Key on
     * every POST; keying off (reference, shipDate) means a crash-replay
     * mid-rate-shop hits SERA's cached response instead of counting as
     * a fresh quote against the account's rate-limit window. UUID v3
     * (name-based) keeps the canonical UUID wire shape SERA documents.
     *
     * <p>Ad-hoc rate shops without an order attached should mint a fresh
     * UUID at the call site rather than pin to a stable key; blank
     * reference numbers are rejected here.
     *
     * @param referenceNumber local order reference; must be non-blank.
     * @param shipDate        the ship-date the rate request uses.
     */
    public static String forStampsRateQuote(String referenceNumber,
                                            java.time.LocalDate shipDate) {
        if (referenceNumber == null || referenceNumber.isBlank()) {
            throw new IllegalArgumentException(
                    "IdempotencyKeys.forStampsRateQuote requires a non-blank referenceNumber");
        }
        if (shipDate == null) {
            throw new IllegalArgumentException(
                    "IdempotencyKeys.forStampsRateQuote requires a non-null shipDate");
        }
        String payload = "stamps-rate|" + referenceNumber.trim() + "|" + shipDate;
        return java.util.UUID.nameUUIDFromBytes(
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /**
     * PR-T6 (audit T-V1) — deterministic Idempotency-Key for Stamps SERA
     * {@code POST /sera/v1/addresses/validate}. Scoped by the fields that
     * uniquely determine the match: city, state, postal code, country.
     * Street-level fields intentionally not keyed — a retry with slightly
     * different casing/whitespace on the street should still hit SERA's
     * dedup cache. UUID v3 for the same canonical-wire-shape reason as
     * {@link #forStampsLabel} and {@link #forStampsManifest}.
     */
    public static String forStampsAddressValidate(String city, String state,
                                                  String postalCode, String country) {
        String payload = "stamps-addr|" + nullTrim(city) + "|" + nullTrim(state) + "|"
                + nullTrim(postalCode) + "|" + nullTrim(country);
        return java.util.UUID.nameUUIDFromBytes(
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /**
     * PR-T5 (audit §5.3) — deterministic Idempotency-Key for Stamps SERA
     * {@code POST /sera/v1/pickups}. SERA requires Idempotency-Key on
     * POSTs; keying off (scope, pickupDate) means a double-click or
     * crash-replay books the SAME pickup under the SAME key. UUID v3
     * (name-based) for canonical UUID wire shape SERA documents.
     *
     * <p>{@code scope} is typically the carrier account number; falls back
     * to the shipper's address line at the call site when no account
     * number is on the request DTO.
     *
     * @param scope      account identifier or address-line fallback.
     * @param pickupDate the booked pickup date.
     */
    public static String forStampsPickup(String scope, java.time.LocalDate pickupDate) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException(
                    "IdempotencyKeys.forStampsPickup requires a non-blank scope");
        }
        if (pickupDate == null) {
            throw new IllegalArgumentException(
                    "IdempotencyKeys.forStampsPickup requires a non-null pickupDate");
        }
        String payload = "stamps-pickup|" + scope.trim() + "|" + pickupDate;
        return java.util.UUID.nameUUIDFromBytes(
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static String nullTrim(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }

}
