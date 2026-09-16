package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.model.Order;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService.EnqueueMpsRequest;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService.EnqueueMpsResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * PR-F2 - fan an MPS ("multi-piece shipment") request into N queue
 * rows so a 1000-package order becomes 1000 sequential USPS label
 * calls, paced by the persistent queue's 55/hour ceiling and
 * per-tenant fair-share slicing.
 *
 * <p>This service is intentionally NARROW: it accepts an already-built
 * multi-package {@link ShipmentRequestDTO} (or, via the {@code forOrder}
 * overload, an orderNo + explicit package count), synthesises one
 * queue row per piece with a distinct shipmentId + sequenceNumber, and
 * delegates the actual persistence to
 * {@link UspsLabelQueueService#enqueueMps(EnqueueMpsRequest)}. It does
 * NOT call any carrier connector - that happens later, on the queue
 * processor's tick, via {@code UspsLabelQueueWiring} (Agent 2's PR-F1
 * class) whose callback runs against each row in turn.
 *
 * <p><b>Synthetic shipmentId scheme.</b> The queue table enforces
 * {@code UNIQUE(shipment_id)}. Single-label enqueue (PR-F1) uses the
 * raw {@code orderNo} as shipmentId. For MPS, we need N distinct
 * shipmentIds for the same order, and they MUST NOT collide with any
 * real orderNo (single-label enqueue for an unrelated order would
 * hit the unique constraint by accident). We solve this by using a
 * negative-offset synthetic key:
 *
 * <pre>
 *   shipmentId = -(parentOrderNo * 100_000L + sequenceNumber)
 * </pre>
 *
 * Negative shipmentIds are clearly synthetic and cannot collide with
 * real orderNos (which are always positive integers). The 100_000
 * multiplier supports up to 99999 pieces per order - two orders of
 * magnitude beyond the 1000-piece stress case from
 * {@code docs/usps-direct-integration.md} scenario A.
 *
 * <p>The MPS-aware branch of the queue processor (Agent 2's territory)
 * must translate the synthetic shipmentId back to
 * ({@code parent_order_no}, {@code sequence_number}) at dispatch time so
 * it knows which piece of which order to hand to USPS. Every row already
 * carries both columns from PR-F2's migration; the callback just reads
 * them off the queue-item entity.
 *
 * <p><b>MPS-only.</b> The DTO-based entry point rejects single-package
 * requests with {@link IllegalArgumentException} - single-label callers
 * belong on {@link UspsLabelQueueService#enqueue(UspsLabelQueueService.EnqueueRequest)}.
 *
 * <p><b>International MPS rejected LOUD at enqueue.</b> USPS Direct v3
 * has no batch endpoint for international shipments - each intl piece
 * would need its own carrier call, and the per-piece dispatcher
 * ({@code UspsMpsPieceDispatcher}, PR-F2.5) builds a lean per-piece
 * {@link ShipmentRequestDTO} that skips the customs-block cascade.
 * Letting an intl MPS order through would reach the dispatcher and
 * USPS would reject the customs-less payload with an opaque error.
 * Both entry points therefore fail fast with a remediation message
 * BEFORE any queue rows are written (transactional integrity - no
 * partial enqueue).
 */
@Slf4j
@Service
public class UspsMpsSplitterService {

    /**
     * Multiplier for the synthetic-shipmentId scheme. 100_000 supports
     * up to 99999 pieces per parent order - two orders of magnitude
     * beyond the 1000-piece stress case. Public for the test harness
     * so assertions can invert the scheme without duplicating the
     * constant.
     */
    public static final long SYNTHETIC_SHIPMENT_ID_MULTIPLIER = 100_000L;

    private final UspsLabelQueueService uspsLabelQueueService;
    /**
     * May be {@code null} in narrow unit tests that only exercise the
     * DTO path via the legacy single-arg constructor; when null,
     * {@link #resolveRecipientCountry} short-circuits to a domestic
     * verdict so pre-intl-guard tests keep working. Production Spring
     * DI always supplies a non-null bean via the primary two-arg
     * constructor below.
     */
    private final OrderRepository orderRepository;

    /**
     * Primary (Spring DI) constructor — both collaborators required
     * in production. {@code @Autowired} disambiguates from the legacy
     * single-arg constructor below, which Spring must not pick.
     */
    @Autowired
    public UspsMpsSplitterService(UspsLabelQueueService uspsLabelQueueService,
                                  OrderRepository orderRepository) {
        this.uspsLabelQueueService = uspsLabelQueueService;
        this.orderRepository = orderRepository;
    }

    /**
     * Legacy single-arg constructor preserved for the pre-intl-guard
     * unit tests that only mock the queue service. Callers on this
     * ctor skip the order-based recipient-country lookup (the DTO
     * path still enforces the intl guard from the DTO's own field);
     * pass {@link #splitAndEnqueueForOrder} inputs at your own risk
     * — they are treated as domestic without an Order lookup. Spring
     * never picks this constructor because {@code @Autowired} on the
     * two-arg ctor above is the sole autowire target.
     */
    public UspsMpsSplitterService(UspsLabelQueueService uspsLabelQueueService) {
        this(uspsLabelQueueService, null);
    }

    /**
     * Primary entry point: given a multi-package {@link ShipmentRequestDTO},
     * fan into N queue rows sharing {@code parentOrderNo}. Every piece
     * gets its own synthetic shipmentId + 1-based sequence.
     *
     * <p>The DTO's contents beyond {@code effectivePackages().size()}
     * are IGNORED for enqueue - the queue processor rebuilds the actual
     * per-piece {@link ShipmentRequestDTO} from
     * {@code Order + packagesJson} at dispatch time via
     * {@code CarrierServiceImpl.buildShipmentRequest}. This service
     * cares only about the piece COUNT (to know how many rows to
     * create) and the sequence numbers (to preserve piece order).
     *
     * @throws IllegalArgumentException if the DTO has fewer than 2
     *         effective packages (single-label callers belong on
     *         {@link UspsLabelQueueService#enqueue}), OR the recipient
     *         country code is non-US (see class javadoc + intl guard
     *         in {@link #splitAndEnqueueForOrder}).
     */
    public EnqueueMpsResult splitAndEnqueue(
            ShipmentRequestDTO multiPackageRequest, String tenantCode, Long parentOrderNo) {
        if (multiPackageRequest == null) {
            throw new IllegalArgumentException("multiPackageRequest must not be null");
        }
        List<PackageDetailDTO> packages = multiPackageRequest.effectivePackages();
        if (packages == null || packages.size() < 2) {
            throw new IllegalArgumentException(
                    "UspsMpsSplitterService called on non-MPS request; use single-label enqueue path.");
        }
        // Intl guard — read the recipient country straight off the DTO
        // (the DTO path skips the DB round-trip; the order path below
        // re-checks after loading the Order so a stale/blank DTO can't
        // sneak an intl order through).
        //
        // REGULATORY_REFERENCE: docs/usps-direct-integration.md §3
        // ("The 3-value USPS_PROVIDER state machine") + §11 ("Open
        // risks + mitigations") — USPS APIs v3 has NO batch endpoint
        // for international; each intl label is a separate carrier
        // call. The per-piece dispatcher (PR-F2.5) builds a lean DTO
        // that omits the customs cascade, so an intl MPS order would
        // reach USPS with a customs-less payload and get rejected with
        // an opaque error. Fail LOUD at enqueue instead of at runtime.
        rejectInternationalMps(
                multiPackageRequest.getRecipientCountryCode(),
                parentOrderNo,
                packages.size());
        return splitAndEnqueueForOrder(parentOrderNo, packages.size(), tenantCode);
    }

    /**
     * Convenience overload for callers (e.g. {@code BulkLabelServiceImpl})
     * that only have an {@code Order} + its {@code packageCount} on hand
     * and don't want to reconstruct a full {@link ShipmentRequestDTO}
     * upfront. Semantically identical to
     * {@link #splitAndEnqueue(ShipmentRequestDTO, String, Long)} - the
     * queue processor builds the per-piece DTO from the Order later
     * anyway.
     *
     * <p>Loads the parent {@link Order} to re-check the recipient
     * country against the intl guard - callers on this path don't
     * carry a DTO, so we resolve the country from
     * {@code Order.shiptoCountryCd}. A blank/null value is treated as
     * US (safe domestic default; matches the connector's fallback in
     * {@code UspsDirectConnector.isInternational}).
     *
     * @param parentOrderNo   Non-null MPS parent order number.
     * @param packageCount    Piece count (>= 2). Values <2 throw IAE
     *                        with the same message as the DTO path.
     * @param tenantCode      Non-blank tenant / client code.
     */
    public EnqueueMpsResult splitAndEnqueueForOrder(
            Long parentOrderNo, int packageCount, String tenantCode) {
        if (parentOrderNo == null) {
            throw new IllegalArgumentException("parentOrderNo must not be null");
        }
        if (packageCount < 2) {
            throw new IllegalArgumentException(
                    "UspsMpsSplitterService called on non-MPS request; use single-label enqueue path.");
        }
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new IllegalArgumentException("tenantCode must not be blank");
        }

        // Intl guard — resolve the recipient country from the loaded
        // Order. Missing row / blank country → treat as US (domestic)
        // so we don't regress on legacy rows that were imported before
        // shipto_country_cd became a hard requirement.
        //
        // REGULATORY_REFERENCE: docs/usps-direct-integration.md §3 + §11
        // — same rationale as the DTO path above. Fail BEFORE any queue
        // rows land so the whole batch either enqueues or rejects
        // atomically.
        String recipientCountry = resolveRecipientCountry(parentOrderNo);
        rejectInternationalMps(recipientCountry, parentOrderNo, packageCount);

        List<EnqueueMpsRequest.PieceRequest> pieces = new ArrayList<>(packageCount);
        for (int seq = 1; seq <= packageCount; seq++) {
            long syntheticShipmentId = syntheticShipmentIdFor(parentOrderNo, seq);
            pieces.add(new EnqueueMpsRequest.PieceRequest(syntheticShipmentId, seq));
        }

        // priority=0 → DEFAULT_PRIORITY (100) in the service impl; bulk-
        // triggered MPS runs at equal priority so the processor's FIFO
        // tie-break preserves piece ordering across the parent's pieces.
        EnqueueMpsRequest req = new EnqueueMpsRequest(
                tenantCode.trim(), parentOrderNo, pieces, 0);

        EnqueueMpsResult result = uspsLabelQueueService.enqueueMps(req);
        log.info("USPS MPS splitter: parentOrderNo={} tenant={} pieces={} → enqueuedCount={} "
                        + "firstStart={} lastComplete={}",
                parentOrderNo, tenantCode, packageCount, result.enqueuedCount(),
                result.estimatedFirstStartAt(), result.estimatedLastCompleteAt());
        return result;
    }

    /**
     * Compute the synthetic shipmentId for a piece. See the class
     * javadoc for the encoding rationale.
     */
    public static long syntheticShipmentIdFor(long parentOrderNo, int sequenceNumber) {
        return -(parentOrderNo * SYNTHETIC_SHIPMENT_ID_MULTIPLIER + sequenceNumber);
    }

    // ================================================================
    // Intl guard helpers
    // ================================================================

    /**
     * Load the parent Order and return its {@code shiptoCountryCd},
     * or {@code null} when the order row / column is empty.
     * A {@code null} return means the guard treats this as US-domestic
     * (safe default; matches the DTO path when the caller passed a
     * blank {@code recipientCountryCode}). Also short-circuits to
     * {@code null} when {@link #orderRepository} was not injected
     * (legacy single-arg-ctor test path).
     */
    private String resolveRecipientCountry(Long parentOrderNo) {
        if (orderRepository == null) {
            return null;
        }
        Optional<Order> maybe = orderRepository.findByOrderNo(
                Math.toIntExact(parentOrderNo));
        return maybe.map(Order::getShiptoCountryCd).orElse(null);
    }

    /**
     * Throw with a remediation-rich message if {@code recipientCountry}
     * is set and non-US. Blank / null country → treated as US-domestic
     * (matches {@code UspsDirectConnector.isInternational}'s implicit
     * fallback). Mirrors the intl rule the connector uses so operators
     * see one consistent verdict at enqueue time, not two conflicting
     * ones (enqueue OK, then dispatch rejects).
     */
    private static void rejectInternationalMps(
            String recipientCountry, Long parentOrderNo, int packageCount) {
        String country = recipientCountry == null ? "" : recipientCountry.trim();
        if (country.isEmpty()) {
            return; // domestic default
        }
        if ("US".equalsIgnoreCase(country)) {
            return; // explicit domestic
        }
        throw new IllegalArgumentException(
                "USPS Direct does not support multi-piece international shipments. "
                        + "USPS APIs v3 accept one intl label per call — an MPS intl order would "
                        + "reject at USPS with an opaque error. "
                        + "Fix: split the order into single-package intl shipments manually, "
                        + "or set USPS_PROVIDER=STAMPS_COM in /settings/system to use Stamps.com's "
                        + "multi-package intl endpoint. "
                        + "Order " + parentOrderNo + " has " + packageCount + " packages with recipient "
                        + "country " + country.toUpperCase(Locale.ROOT) + " — refusing to enqueue.");
    }
}
