package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService.EnqueueMpsRequest;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService.EnqueueMpsResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
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
     *         {@link UspsLabelQueueService#enqueue}).
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
}
