package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.dto.ApiResponse;
import com.multiship.backend.dto.LabelGenerationResponse;
import com.multiship.backend.model.UspsLabelQueueItem;
import com.multiship.backend.service.CarrierService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;

/**
 * PR-F1 Agent-2 — wiring bridge between the persistent USPS Direct
 * label queue (Agent-1) and the existing single-label carrier
 * pipeline.
 *
 * <p>At bean-init time we register a {@link LabelProcessCallback} on
 * Agent-1's {@link UspsLabelQueueProcessor}. Whenever the processor
 * picks a queue row up (after the 55/hr rate limiter has gated it +
 * the fair scheduler has picked the next tenant) it invokes the
 * callback with a {@link UspsLabelQueueItem} carrying the tenant code
 * + shipmentId (i.e. orderNo in our schema — a shipmentId is the same
 * key the bulk-label workers push into
 * {@link UspsLabelQueueService.EnqueueRequest#shipmentId()}).
 *
 * <p>The callback then delegates to
 * {@link CarrierService#generateLabel(Long, UserDetails, String, Long)}
 * — the same single-label pipeline the manual "Generate label" button
 * uses. This path already resolves the carrier account, calls
 * {@code UspsDirectConnector.createShipment}, persists the tracking
 * number on {@code order_tracking}, and dispatches downstream (SFTP /
 * webhook / etc). No connector-specific logic lives in the wiring.
 *
 * <p><b>No recursion risk.</b>
 * {@code CarrierService.generateLabel} is the SINGLE-label path;
 * {@code BulkLabelServiceImpl.maybeEnqueueUspsDirect} only fires from
 * the BULK path. So a queue item invoking generateLabel does NOT
 * re-enqueue itself.
 *
 * <p>Idempotency key format: {@code usps-queue-{queueItemId}}. That
 * makes retries from Agent-1's processor safe — if the queue row goes
 * FAILED and the processor re-picks it, the second call hits
 * {@code CarrierServiceImpl.generateLabel}'s idempotency cache and
 * returns the first attempt's result rather than double-charging USPS.
 *
 * <p><b>System user.</b> Queue-processor threads have no HTTP request
 * context, so we synthesize a "usps-queue" username. Downstream
 * {@code CarrierServiceImpl.resolveUser()} tolerates the missing User
 * row by falling back to the account cascade — the bulk-label path
 * already exercises this pattern.
 *
 * <p><b>PR-F2.5 — MPS per-piece dispatch.</b> Queue rows enqueued by
 * {@link UspsMpsSplitterService} (PR-F2) carry a negative synthetic
 * shipmentId plus a non-null {@code parentOrderNo} + {@code sequenceNumber}.
 * Those rows can't route through {@code CarrierService.generateLabel}
 * (which treats one order as one shipment) so the callback delegates
 * them to {@link UspsMpsPieceDispatcher#dispatchPiece(UspsLabelQueueItem)}
 * — one HTTP label call per piece per queue tick. See the dispatcher's
 * class javadoc for the full flow.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class UspsLabelQueueWiring {

    /** Fixed system-user name for label calls originating from the
     *  queue. Chosen to be greppable in logs + distinct from real
     *  operator usernames. */
    static final String QUEUE_SYSTEM_USER = "usps-queue-processor";

    private final UspsLabelQueueProcessor processor;
    private final CarrierService carrierService;
    /**
     * PR-F2.5 — per-piece MPS dispatch. Injected as a first-class
     * collaborator (not looked up lazily) so a missing bean fails fast
     * at context startup rather than at the first MPS queue tick. The
     * dispatcher itself is a plain {@code @Service} with pure-JPA +
     * connector dependencies; it has no scheduled tick of its own.
     */
    private final UspsMpsPieceDispatcher mpsPieceDispatcher;

    /**
     * Register {@link #processQueueItem} as the callback the processor
     * invokes when it picks a queue row. {@code @PostConstruct} runs
     * once per bean init — the processor guarantees callbacks are
     * invoked on its worker pool, so this method returns immediately.
     */
    @PostConstruct
    public void wire() {
        processor.registerCallback(this::processQueueItem);
        log.info("USPS Direct queue wiring: callback registered on processor {}",
                processor.getClass().getSimpleName());
    }

    /**
     * Callback body — runs on Agent-1's processor thread. Return the
     * tracking number on success; throw on any error and Agent-1's
     * processor marks the queue row FAILED + increments {@code retry_count}.
     *
     * <p>Any exception thrown here surfaces to Agent-1 verbatim — no
     * try/catch swallowing. Agent-1's processor already logs + persists
     * the failure detail; adding another catch layer would just
     * duplicate that.
     */
    String processQueueItem(UspsLabelQueueItem item) throws Exception {
        if (item == null || item.getShipmentId() == null) {
            throw new IllegalArgumentException(
                    "USPS queue item is missing shipmentId — cannot process.");
        }
        // PR-F2.5 — MPS pieces (parentOrderNo != null) route to the
        // per-piece dispatcher. Each piece = one USPS Direct label call
        // = one queue tick. Non-MPS rows continue through the single-
        // label path below (CarrierService.generateLabel) unchanged.
        if (item.getParentOrderNo() != null) {
            log.debug("USPS Direct queue: MPS piece dispatch item={} parent={} seq={}",
                    item.getId(), item.getParentOrderNo(), item.getSequenceNumber());
            return mpsPieceDispatcher.dispatchPiece(item);
        }
        Long orderNo = item.getShipmentId();
        String idempotencyKey = "usps-queue-" + item.getId();
        UserDetails systemUser = buildSystemUser();
        log.debug("USPS Direct queue: processing item {} (tenant={}, order={})",
                item.getId(), item.getTenantCode(), orderNo);

        ApiResponse<LabelGenerationResponse> resp = carrierService
                .generateLabel(orderNo, systemUser, idempotencyKey, null);

        if (resp == null || resp.getData() == null) {
            throw new IllegalStateException(
                    "USPS Direct queue: label service returned no data for order "
                            + orderNo + " (queue item " + item.getId() + ").");
        }
        LabelGenerationResponse label = resp.getData();
        if (!StringUtils.hasText(label.getTrackingNumber())) {
            String reason = StringUtils.hasText(label.getMessage())
                    ? label.getMessage()
                    : ("USPS Direct label call returned no tracking number for order " + orderNo);
            throw new IllegalStateException(reason);
        }
        log.info("USPS Direct queue: item {} done (order {} → tracking {})",
                item.getId(), orderNo, label.getTrackingNumber());
        return label.getTrackingNumber();
    }

    /** Build a placeholder UserDetails for queue-processor threads.
     *  Never authenticates against a real User row — downstream only
     *  reads {@link UserDetails#getUsername()}. Kept private + static
     *  so it can't be reused elsewhere by accident. */
    private static UserDetails buildSystemUser() {
        return User.withUsername(QUEUE_SYSTEM_USER)
                .password("")
                .authorities("ROLE_SYSTEM")
                .build();
    }
}
